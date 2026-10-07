package com.neocat.alert.infra.job;

import com.neocat.alert.domain.engine.AlertEngine;
import com.neocat.alert.domain.rule.AlertRule;
import com.neocat.alert.domain.rule.AlertRuleRepository;
import com.neocat.alert.domain.engine.AlertWindowState;
import com.neocat.common.config.AlertConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.Clock;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 告警分钟判定调度（PRD 06 §5、§11，链路 30）。
 *
 * <p>每个**完整分钟点**到达后（延迟由 {@code alert.evaluate-delay-seconds} 控制），
 * 对全部已启用规则回看 X 点滑动窗口并判定。
 *
 * <p>关键语义：
 * <ul>
 *   <li>只使用**已完成的分钟点**：当前分钟仍在写入，提前判定会基于不完整数据；</li>
 *   <li>窗口状态保存在内存（{@link Map}），因为一期**不保存告警触发历史**，
 *       窗口只服务于判定本身（PRD 06 §11）；</li>
 *   <li>单条规则失败不影响其他规则：循环内逐条 try/catch；</li>
 *   <li>无有效收件人时仍评估但不发送（由 {@link AlertEngine} 保证）。</li>
 * </ul>
 */
@Component
@org.springframework.context.annotation.DependsOn("alertConfig")
public class AlertEvaluationJob {

    private static final Logger log = LoggerFactory.getLogger(AlertEvaluationJob.class);

    private final AlertRuleRepository repository;

    private final AlertEngine engine;

    private final Clock clock;

    private volatile long lastScheduledMinute;

    /** 规则 ID → 窗口状态。一期不落库，进程重启后窗口从零重建（与「启用后重建」语义一致）。 */
    private final Map<Long, AlertWindowState> windowStates;

    public AlertEvaluationJob(AlertRuleRepository repository, AlertEngine engine) {
        this(repository, engine, Clock.systemUTC());
    }
    @org.springframework.beans.factory.annotation.Autowired
    public AlertEvaluationJob(AlertRuleRepository repository, AlertEngine engine, Clock clock) {
        this.repository = repository;
        this.engine = engine;
        this.clock = clock;
        this.lastScheduledMinute = Long.MIN_VALUE;
        this.windowStates = new ConcurrentHashMap<>();
    }
    /**
     * 每分钟执行一次判定。
     *
     * <p>每秒检查一次可热更新的判定延迟，整分钟只调度一次；
     * 判定目标为上一个已完成的分钟点。
     */
    @Scheduled(fixedDelay = 1000)
    public synchronized void evaluate() {
        if (AlertConfig.EVALUATE_DELAY_SECONDS < 0) throw new IllegalStateException("Alert evaluation delay must not be negative");
        long minute = clock.instant().minusSeconds(AlertConfig.EVALUATE_DELAY_SECONDS)
                .truncatedTo(ChronoUnit.MINUTES).minusSeconds(60).toEpochMilli();
        // 延迟加大不能回头重判旧分钟，否则会重发并打乱滑动窗口。
        if (minute > lastScheduledMinute) {
            lastScheduledMinute = minute;
            evaluateAt(minute);
        }
    }
    /**
     * 在给定分钟点上执行一次全量判定。
     *
     * <p>抽成独立方法以便测试与运维手动触发（补齐停机期间缺口时也只重放已完成分钟点）。
     */
    public void evaluateAt(long minuteEpochMillis) {
        for (AlertRule rule : repository.enabledRules()) {
            try {
                long baseline = rule.getStateSince() == null ? minuteEpochMillis : rule.getStateSince();
                AlertWindowState state = windowStates.compute(rule.getId(), (id, existing) ->
                        existing == null || existing.getBaselineAt() < baseline
                                ? AlertWindowState.empty(id, baseline) : existing);
                var result = engine.onMinute(rule, state, minuteEpochMillis);
                windowStates.put(rule.getId(), result.getState());
                if (result.isTriggered()) {
                    log.info("规则 {}（{}）在 {} 触发，通知 {} 条",
                            rule.getId(), rule.getName(), minuteEpochMillis, result.getNotifications().size());
                }
            } catch (Throwable t) {
                // 单条规则失败不影响其他规则
                log.warn("规则 {} 判定失败，已跳过本次", rule.getId(), t);
            }
        }
    }
    /**
     * 规则启用/编辑后重置窗口。
     *
     * <p>与 PRD 06 §3.2、§3.3 一致：启用后只使用启用时刻之后的完整分钟点；
     * 编辑保存后窗口清零。
     */
    public void resetWindow(long ruleId, long baselineAt) {
        windowStates.put(ruleId, AlertWindowState.empty(ruleId, baselineAt));
    }
    /** 丢弃某规则的窗口状态（删除规则时调用，避免内存泄漏）。 */
    public void forget(long ruleId) {
        windowStates.remove(ruleId);
    }
    /** 当前维护的窗口数量（观测用）。 */
    public int trackedWindows() {
        return windowStates.size();
    }
    /** 判定延迟参数（观测用）。 */
    public int evaluationDelaySeconds() {
        return AlertConfig.EVALUATE_DELAY_SECONDS;
    }
}
