package com.neocat.alert.domain;

import java.math.BigDecimal;

import com.google.common.collect.Lists;
import com.neocat.alert.domain.entity.AlertRule;
import com.neocat.alert.domain.entity.vo.rule.Combinator;
import com.neocat.alert.domain.entity.vo.rule.Condition;
import com.neocat.alert.domain.gateway.MinutePointSourceGateway;
import com.neocat.alert.domain.event.AlertTriggeredEvent;
import com.neocat.alert.domain.entity.vo.cursor.AlertEvalCursor;
import com.neocat.alert.domain.entity.vo.result.EvaluationResult;
import com.neocat.alert.domain.entity.vo.source.MinutePoint;
import com.neocat.common.time.clock.TimeProvider;
import com.neocat.query.domain.stat.Stat;
import lombok.RequiredArgsConstructor;
import org.apache.commons.lang3.BooleanUtils;
import org.apache.commons.lang3.tuple.Pair;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.modulith.NamedInterface;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.apache.commons.collections4.CollectionUtils;
import org.apache.commons.collections4.MapUtils;
import org.apache.commons.collections4.ListUtils;

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
@RequiredArgsConstructor
// fixme: 把 AlertRuleService, PreviewService 和 AlertEngine 逻辑合并后提到 application 层,
//  当前的逻辑已经算 rule 和 windowState 两个聚合逻辑的编排了
public class AlertEngine {

    private final MinutePointSourceGateway minutePointSourceGateway;

    private final ApplicationEventPublisher eventPublisher;

    public EvaluationResult onMinute(AlertRule rule, AlertEvalCursor cursor, Long curMinute) {

        if (Objects.isNull(cursor)) {
            throw new IllegalArgumentException("state cannot be null");
        }

        // 基线之前的点不参与窗口
        if (curMinute < cursor.getBaselineAt()) {
            return new EvaluationResult(false, cursor);
        }

        // 获取需要计算的时间区间
        int window = Math.max(1, rule.getWindowPoints());
        var evalTimePoints = TimeProvider.minuteWindow(curMinute, window).stream().map(Instant::toEpochMilli).toList();

        AlertEvalCursor curEvalCursor = AlertEvalCursor.of(rule.getId(), cursor.getBaselineAt(), curMinute);

        if (evalTimePoints.size() < window) {
            return new EvaluationResult(false, curEvalCursor);
        }

        // 判断点是否触发
        Pair<Boolean, List<MinutePoint>> evalResult = pointSatisfied(rule, evalTimePoints);
        if (BooleanUtils.isFalse(evalResult.getLeft())) {
            return new EvaluationResult(false, curEvalCursor);
        }

        // todo: 优化文案.
        String message = rule.getName() + " 触发于 " + curMinute;
        var triggeredEvent = AlertTriggeredEvent.builder()
                .ruleId(rule.getId())
                .ruleName(rule.getName())
                .recipients(rule.getRecipients())
                .channels(rule.getChannels())
                .windowSize(rule.getWindowPoints())
                .triggeredAt(curMinute)
                .triggerPoints(evalResult.getRight())
                .message(message)
                .build();

        eventPublisher.publishEvent(triggeredEvent);
        return new EvaluationResult(true, curEvalCursor);
    }

    /**
     * 判定多个个分钟点的条件组合。
     *
     * <p>任一所需要统计项缺失 → 返回 false（缺数打断窗口）。
     */
    private Pair<Boolean, List<MinutePoint>> pointSatisfied(AlertRule rule, List<Long> minutes) {

        List<Stat> required = rule.requiredStats();

        List<MinutePoint> minutePoints =
                minutePointSourceGateway.batchMinuteValues(rule.getTarget(), minutes, required);

        Map<Long, MinutePoint> minutePointMap = ListUtils.emptyIfNull(minutePoints).stream()
                .collect(Collectors.toMap(
                        MinutePoint::getMinute, Function.identity(),
                        (first, second) -> second
                ));

        if (MapUtils.isEmpty(minutePointMap)) {
            return Pair.of(false, Lists.newArrayList());
        }
        for (Long minute : minutes) {
            MinutePoint minutePoint = minutePointMap.get(minute);
            if (Objects.isNull(minutePoint)) {
                return Pair.of(false, Lists.newArrayList());
            }
            Map<Stat, BigDecimal> values = minutePoint.getValues();
            if (BooleanUtils.isFalse(combine(rule, required, values))) {
                return Pair.of(false, Lists.newArrayList());
            }
        }
        return Pair.of(true, minutePoints);
    }

    /**
     * AND：全部条件满足；OR：任一条件满足。
     */
    private boolean combine(AlertRule rule, List<Stat> required, Map<Stat, BigDecimal> values) {
        if (CollectionUtils.isEmpty(required) || MapUtils.isEmpty(values)) {
            return false;
        }
        boolean allMatch = required.stream().allMatch(values::containsKey);
        if (BooleanUtils.isFalse(allMatch)) {
            return false;
        }

        List<Condition> conditions = rule.getConditions();
        if (CollectionUtils.isEmpty(conditions)) {
            return false;
        }
        boolean and = Objects.equals(rule.getCombinator(), Combinator.AND);
        boolean aggregate = and;
        for (Condition condition : conditions) {
            boolean matched = condition.matches(values.get(condition.getStat()));
            aggregate = and ? (aggregate && matched) : (aggregate || matched);
        }
        return aggregate;
    }

}
