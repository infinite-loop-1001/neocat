package com.neocat.alert.application.job;

import com.neocat.alert.config.AlertConfig;
import com.neocat.alert.domain.AlertEngine;
import com.neocat.alert.domain.entity.vo.cursor.AlertEvalCursor;
import com.neocat.alert.domain.entity.AlertRule;
import com.neocat.alert.domain.repository.AlertRuleRepository;
import com.neocat.common.time.clock.TimeProvider;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.DependsOn;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

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
@DependsOn("alertConfig")
@Slf4j
@Component
public class AlertEvaluationJob {

    private final AlertEngine alertEngine;

    private final AlertRuleRepository alertRuleRepository;

    private volatile long lastScheduledMinute;

    public AlertEvaluationJob(AlertRuleRepository alertRuleRepository, AlertEngine alertEngine) {
        this.alertRuleRepository = alertRuleRepository;
        this.alertEngine = alertEngine;
        this.lastScheduledMinute = Long.MIN_VALUE;
    }
    /**
     * 每分钟执行一次判定。
     *
     * <p>每秒检查一次可热更新的判定延迟，整分钟只调度一次；
     * 判定目标为上一个已完成的分钟点。
     */
    @Scheduled(fixedDelay = 1000)
    public synchronized void evaluate() {

        long minute = TimeProvider.delayedMinuteStart(AlertConfig.EVALUATE_DELAY_SECONDS)
                .minusSeconds(60).toEpochMilli();

        // 延迟加大不能回头重判旧分钟，否则会重发并打乱滑动窗口。
        if (minute <= lastScheduledMinute) {
            return;
        }
        lastScheduledMinute = minute;
        evaluateAt(minute);
    }

    /**
     * 在给定分钟点上执行一次全量判定。
     *
     * <p>抽成独立方法以便测试与运维手动触发（补齐停机期间缺口时也只重放已完成分钟点）。
     */
    private void evaluateAt(Long minuteEpochMillis) {
        List<AlertRule> alertRules = alertRuleRepository.enabledRules();
        for (AlertRule rule : alertRules) {
            try {
                // todo: 查询告警规则的最新游标 (查询规则: 查询当前规则 id 下, 最新未被删除的 cursor)
                AlertEvalCursor evalCursor = new AlertEvalCursor();
                var result = alertEngine.onMinute(rule, evalCursor, minuteEpochMillis);
                if (result.isTriggered()) {
                    log.info("规则 {}（{}）在 {} 触发", rule.getName(), rule.getId(), minuteEpochMillis);
                }
            } catch (Throwable t) {
                // 单条规则失败不影响其他规则
                // todo: 细化异常场景
                log.warn("规则 {} 判定失败，已跳过本次", rule.getId(), t);
            }
        }
    }
}
