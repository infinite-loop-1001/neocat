package com.neocat.alert.domain.engine;

import com.neocat.alert.domain.rule.AlertRule;
import com.neocat.alert.domain.rule.Combinator;
import com.neocat.alert.domain.rule.Condition;
import com.neocat.query.domain.stat.Stat;
import org.springframework.modulith.NamedInterface;
import org.springframework.stereotype.Service;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.apache.commons.collections4.CollectionUtils;
import org.apache.commons.collections4.MapUtils;

/**
 * 预告警试算（PRD 06 §4）。
 *
 * <p>判定顺序（决定三态归属）：
 * <pre>
 * 对 [t-X+1, t] 的每个点：
 *   1. 取该点各条件统计项的值
 *   2. 若任一必需统计项缺失 → 该点 unknown（缺数）
 *   3. 否则按 AND/OR 合并条件
 * 汇总：
 *   存在 unknown 点 → INSUFFICIENT_DATA （缺数不当 0，也不当作不满足而报"不会触发"）
 *   全部点满足      → TRIGGER
 *   否则            → NO_TRIGGER
 * </pre>
 *
 * <p>「数据不足」优先于「不会触发」：若把缺数点当作不满足，
 * 用户会看到「当前不会触发」并误以为规则正常，而真实情况是**无法判断**。
 * 这正是 PRD 06 §4 要求明确显示数据不足的原因。
 */
@Service
@NamedInterface("alert")
public class PreviewService {

    private final MinutePointSource points;

    public PreviewService(MinutePointSource points) {
        this.points = points;
    }

    // rules: 复杂逻辑需要注释解释流程, 以及为什么这么做
    public PreviewResult preview(AlertRule rule, long latestMinute) {
        int window = Math.max(1, rule.getWindowPoints());
        List<Stat> requiredStats = requiredStats(rule);
        List<PreviewResult.PointEvaluation> evaluations = new ArrayList<>(window);
        boolean anyUnknown = false;

        // fixme: 这里 MinutePointSource 需要提供批量接口, 不能循环调用每分钟的指标值
        for (int i = 0; i < window; i++) {
            long minute = latestMinute - (long) i * 60_000L;
            Map<Stat, Double> values = points.values(rule.getTarget(), minute, requiredStats);

            String missing = firstMissing(requiredStats, values);
            if (Objects.nonNull(missing)) {
                anyUnknown = true;
                evaluations.add(new PreviewResult.PointEvaluation(minute, false, false, missing));
                continue;
            }
            evaluations.add(new PreviewResult.PointEvaluation(minute, true, combine(rule, values), null));
        }

        if (anyUnknown) {
            return PreviewResult.insufficient(evaluations);
        }
        boolean allSatisfied = evaluations.stream().allMatch(PreviewResult.PointEvaluation::isSatisfied);
        return new PreviewResult(allSatisfied
                ? PreviewResultType.TRIGGER
                : PreviewResultType.NO_TRIGGER, List.copyOf(evaluations));
    }
    /**
     * 合并单点的条件组合。
     *
     * <p>AND：全部条件满足；OR：任一条件满足。
     * 缺数由调用方预先排除，此处只处理有值的点。
     */
    // fixme: 这里逻辑需要收束到 AlterWindowStateService 里
    public boolean combine(AlertRule rule, Map<Stat, Double> values) {
        List<Condition> conditions = rule.getConditions();
        if (CollectionUtils.isEmpty(conditions)) {
            return false;
        }
        boolean and = rule.getCombinator() == Combinator.AND;
        boolean aggregate = and;
        for (Condition condition : conditions) {
            Double value = values.get(condition.getStat());
            boolean matched = condition.matches(value);
            aggregate = and ? (aggregate && matched) : (aggregate || matched);
        }
        return aggregate;
    }

    // ── 内部 ─────────────────────────────────────────────────

    /** 该规则需要的全部统计项：条件统计项 ∪ 目标公式统计项。 */
    // fixme: 这个逻辑应该收束成 AlterRule 聚合内部逻辑
    private List<Stat> requiredStats(AlertRule rule) {
        List<Stat> stats = new ArrayList<>();
        if (Objects.nonNull(rule.getTarget()) && Objects.nonNull(rule.getTarget().getFormulaStats())) {
            stats.addAll(rule.getTarget().getFormulaStats());
        }
        if (Objects.nonNull(rule.getConditions())) {
            rule.getConditions().stream().map(Condition::getStat).filter(s -> !stats.contains(s)).forEach(stats::add);
        }
        return List.copyOf(stats);
    }
    /**
     * 返回第一个缺失的统计项名；全部齐备返回 null。
     *
     * <p>注意：值为 0 是**已知值**，不算缺失；只有 {@code null} 或键不存在才算缺数。
     */
    // fixme: 这里逻辑需要收束到 AlterWindowStateService 里
    private String firstMissing(List<Stat> requiredStats, Map<Stat, Double> values) {
        for (Stat stat : requiredStats) {
            if (MapUtils.isEmpty(values) || !values.containsKey(stat) || Objects.isNull(values.get(stat))) {
                return displayOf(stat);
            }
        }
        return null;
    }
    private String displayOf(Stat stat) {
        return switch (stat) {
            case FAILURE_RATE -> "failureRate";
            case AVG -> "avgDuration";
            default -> stat.name().toLowerCase(java.util.Locale.ROOT);
        };
    }
}
