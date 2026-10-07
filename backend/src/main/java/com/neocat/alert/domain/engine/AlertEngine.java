package com.neocat.alert.domain.engine;

import com.neocat.alert.domain.recipient.RecipientGateway;
import com.neocat.alert.domain.rule.AlertChannel;
import com.neocat.alert.domain.rule.AlertRule;
import com.neocat.alert.domain.rule.Combinator;
import com.neocat.alert.domain.rule.Condition;

import com.neocat.query.domain.stat.Stat;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.ToString;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.modulith.NamedInterface;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 滑动窗口判定与分钟调度（PRD 06 §3.2、§5、§6）。
 *
 * <p>判定流程：
 * <pre>
 * onMinute(rule, state, t):
 *   1. 若 t 早于基线 → 忽略（启用前的历史点不参与）
 *   2. 追加 t 到窗口，只保留最近 X 个点（时间升序）
 *   3. 窗口未满 X → 不触发
 *   4. 逐点计算条件组合；任一点缺数或未满足 → 不触发
 *   5. 全部满足 → 触发，向有效收件人按通道各发一次
 * </pre>
 *
 * <p>基线语义（PRD 06 §3.2、§9）是这套逻辑里最容易做错的部分：
 * 启用、编辑后重新启用、补收件人都会设置新基线，因此
 * **早于基线的点即使有数据、即使满足条件，也不得进入窗口**。
 * 本实现把该判断放在最前面，使这条规则无法被后续分支绕过。
 *
 * <p>缺数（PRD 06 §6）：未知点既不满足任何条件，也会使整个窗口不触发。
 * 这里以 {@code pointSatisfied} 返回 false 统一表达，因为「缺数」与「不满足」
 * 对窗口判定的效果相同——都是不触发；二者的区分由预告警负责呈现。
 *
 * <p>不落历史（PRD 06 §11）：本类不写任何持久化记录，只返回判定结果与已发送通知。
 */
@Service
@NamedInterface("alert")
// fixme: 把 AlertRuleService, PreviewService 和 AlertEngine 逻辑合并后提到 application 层,
//  当前的逻辑已经算 rule 和 windowState 两个聚合逻辑的编排了
public class AlertEngine {

    private final MinutePointSource points;

    private final Notifier notifier;

    private final RecipientGateway recipients;

    private final NotificationDispatcher dispatcher;

    public AlertEngine(MinutePointSource points, Notifier notifier) {
        this.points = points;
        this.notifier = notifier;
        this.recipients = null;
        this.dispatcher = null;
    }

    @Autowired
    public AlertEngine(MinutePointSource points, Notifier notifier, RecipientGateway recipients,
                       NotificationDispatcher dispatcher) {
        this.points = points;
        this.notifier = notifier;
        this.recipients = recipients;
        this.dispatcher = dispatcher;
    }

    public EvaluationResult onMinute(AlertRule rule, AlertWindowState state, long minute) {
        int window = Math.max(1, rule.getWindowPoints());
        // fixme: 如果当前窗口状态为空需要抛异常, 不能当作空状态往下算
        // rules: 赋值表达式右侧是一个表达式时, 右边的表达式需要用 () 包裹
        AlertWindowState current = Objects.isNull(state)
                ? AlertWindowState.empty(rule.getId(), minute)
                : state;

        // 基线之前的点不参与窗口
        if (minute < current.getBaselineAt()) {
            return new EvaluationResult(false, List.of(), current);
        }

        List<Long> appended = new ArrayList<>(current.getPoints());
        // rules: 容器判断是否包含元素使用 Apache CollectionUtils
        if (!appended.contains(minute)) {
            appended.add(minute);
        }
        appended.sort(Long::compareTo);
        List<Long> trimmed = appended.size() <= window
                ? appended
                // rules: 禁止使用 List.subList(), 返回的 subList 是个伪 List
                : appended.subList(appended.size() - window, appended.size());
        AlertWindowState advanced = new AlertWindowState(rule.getId(), current.getBaselineAt(), List.copyOf(trimmed));

        if (trimmed.size() < window) {
            return new EvaluationResult(false, List.of(), advanced);
        }

        // fixme: 这里 MinutePointSource 需要提供批量接口, 不能循环调用每分钟的指标值
        boolean allSatisfied = trimmed.stream().allMatch(m -> pointSatisfied(rule, m));
        if (!allSatisfied) {
            return new EvaluationResult(false, List.of(), advanced);
        }

        List<AlertNotification> sent = notify(rule, minute);
        return new EvaluationResult(true, sent, advanced);
    }
    /**
     * 判定单个分钟点的条件组合。
     *
     * <p>任一所需要统计项缺失 → 返回 false（缺数打断窗口）。
     */
    public boolean pointSatisfied(AlertRule rule, long minute) {
        List<Stat> required = requiredStats(rule);
        Map<Stat, Double> values = points.values(rule.getTarget(), minute, required);
        for (Stat stat : required) {
            if (Objects.isNull(values) || !values.containsKey(stat) || Objects.isNull(values.get(stat))) {
                return false;
            }
        }
        return combine(rule, values);
    }

    // ── 内部 ─────────────────────────────────────────────────

    /** AND：全部条件满足；OR：任一条件满足。 */
    private boolean combine(AlertRule rule, Map<Stat, Double> values) {
        List<Condition> conditions = rule.getConditions();
        if (Objects.isNull(conditions) || conditions.isEmpty()) {
            return false;
        }
        // rules: 判断相等使用 Objects.equals(), 防止出现任一比较对象为 null
        boolean and = rule.getCombinator() == Combinator.AND;
        boolean aggregate = and;
        for (Condition condition : conditions) {
            boolean matched = condition.matches(values.get(condition.getStat()));
            aggregate = and ? (aggregate && matched) : (aggregate || matched);
        }
        return aggregate;
    }

    // fixme: 这个逻辑应该收束成 AlterRule 聚合内部逻辑
    private List<Stat> requiredStats(AlertRule rule) {
        // fixme: 这里去重直接使用 stream 流就可以
        Map<Stat, Boolean> unique = new LinkedHashMap<>();
        if (Objects.nonNull(rule.getTarget()) && Objects.nonNull(rule.getTarget().getFormulaStats())) {
            rule.getTarget().getFormulaStats().forEach(stat -> unique.put(stat, true));
        }
        if (Objects.nonNull(rule.getConditions())) {
            rule.getConditions().forEach(condition -> unique.put(condition.getStat(), true));
        }
        return List.copyOf(unique.keySet());
    }

    /**
     * 发送通知。
     *
     * <p>无有效收件人时返回**空列表**：规则继续评估但不发送（PRD 06 §9）。
     * 每个通道各发一次（PRD 06 §10）。
     */
    private List<AlertNotification> notify(AlertRule rule, long minute) {
        List<Long> recipients = Objects.isNull(rule.getRecipients()) ? List.of() : rule.getRecipients();
        if (Objects.nonNull(this.recipients)) {
            recipients = recipients.stream().filter(this.recipients::isEnabled)
                    .filter(id -> Objects.isNull(rule.getOrgId()) || this.recipients.isEffectiveMember(id, rule.getOrgId()))
                    .toList();
        }
        if (recipients.isEmpty() || Objects.isNull(rule.getChannels()) || rule.getChannels().isEmpty()) {
            return List.of();
        }
        if (Objects.nonNull(dispatcher)) {
            return dispatcher.dispatch(rule, recipients, minute);
        }
        String message = rule.getName() + " 触发于 " + minute;
        List<AlertNotification> sent = new ArrayList<>();
        for (AlertChannel channel : rule.getChannels()) {
            AlertNotification notification = new AlertNotification(
                    rule.getId(), rule.getName(), List.copyOf(recipients), channel, message, minute);
            notifier.send(notification);
            sent.add(notification);
        }
        return List.copyOf(sent);
    }

    /**
     * 一次分钟判定的结果。
     */
    // fixme: 单独抽成一个值对象
    @NamedInterface("alert")
    @Getter
    @EqualsAndHashCode
    @ToString
    public static class EvaluationResult {
        private final boolean triggered;

        private final List<AlertNotification> notifications;

        private final AlertWindowState state;

        public EvaluationResult(boolean triggered, List<AlertNotification> notifications, AlertWindowState state) {
            this.triggered = triggered;
            this.notifications = notifications;
            this.state = state;
        }

    }
}

