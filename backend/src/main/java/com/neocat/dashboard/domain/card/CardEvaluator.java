package com.neocat.dashboard.domain.card;

import com.neocat.dashboard.domain.formula.Formula;
import com.neocat.dashboard.domain.formula.FormulaParser;

import com.neocat.query.domain.stat.Stat;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 卡片求值器（PRD 05 §4、§5，技术方案 02 §9.2）。
 *
 * <p>求值顺序（与 PRD 05 §5 的优先级一致）：
 * <ol>
 *   <li><b>先判缺数</b>：任一输入为 {@code null} → 缺口。缺数不当作 0，
 *       缺口结果绝不能是某个数值（避免用户以为该点有数据）；</li>
 *   <li>再判除零：分母求值为 0 → 不可计算。这与缺口是不同语义，
 *       前端需要分别展示「数据缺失」与「不可计算」；</li>
 *   <li>正常按 AST 求值。</li>
 * </ol>
 *
 * <p>求值是**逐桶独立**的：不存在「沿用上一点」的路径，
 * 因为每个桶都从头计算，没有跨桶状态。
 */
@org.springframework.modulith.NamedInterface("dashboard")
public class CardEvaluator {

    public static final String INVALID_TARGET = "INVALID_TARGET";

    /**
     * 对单个桶求值。
     */
    public CardPoint evaluate(Formula formula, Map<Stat, Double> inputs, long bucketStart, long bucketEnd) {
        if (formula == null) {
            return new CardPoint(bucketStart, bucketEnd, null, CardPointOutcome.GAP, List.of());
        }

        Set<String> missing = new LinkedHashSet<>();
        for (Stat stat : formula.referencedStats()) {
            Double value = inputs == null ? null : inputs.get(stat);
            if (value == null) {
                missing.add(displayOf(stat));
            }
        }
        if (!missing.isEmpty()) {
            return new CardPoint(bucketStart, bucketEnd, null,
                    CardPointOutcome.GAP, List.copyOf(missing));
        }

        EvalResult result = eval(formula, inputs);
        if (result.isDivideByZero()) {
            return new CardPoint(bucketStart, bucketEnd, null,
                    CardPointOutcome.DIVIDE_BY_ZERO, List.of());
        }
        return new CardPoint(bucketStart, bucketEnd, result.getComputed(), CardPointOutcome.OK, List.of());
    }
    /**
     * 校验卡片目标与公式：一个服务 + 一个指标对象，公式单位必须兼容。
     */
    public String validateTarget(Card card) {
        if (card == null) {
            return INVALID_TARGET;
        }
        if (card.getService() == null || card.getService().isBlank()) {
            return INVALID_TARGET;
        }
        if (card.getTargetKind() == null || card.getTargetKind().isBlank()) {
            return INVALID_TARGET;
        }
        boolean hasObject = (card.getTargetType() != null && !card.getTargetType().isBlank())
                || (card.getTargetName() != null && !card.getTargetName().isBlank())
                || (card.getMetricLabels() != null && !card.getMetricLabels().isBlank());
        if (!hasObject) {
            return INVALID_TARGET;
        }
        FormulaParser.ParseOutcome parsed = new FormulaParser().parse(card.getFormula());
        if (!parsed.valid()) {
            return parsed.getError();
        }
        return null;
    }

    // ── 内部求值 ─────────────────────────────────────────────

    private EvalResult eval(Formula formula, Map<Stat, Double> inputs) {
        if (formula instanceof Formula.Constant constant) {
            return EvalResult.of(constant.getValue());
        }
        if (formula instanceof Formula.Ref ref) {
            return EvalResult.of(inputs.get(ref.getStat()));
        }
        if (formula instanceof Formula.Aggregate aggregate) {
            // 单桶内聚合退化为取值本身：卡片以桶为单位，聚合已由查询层完成
            return EvalResult.of(inputs.get(aggregate.getStat()));
        }
        Formula.Binary binary = (Formula.Binary) formula;
        EvalResult left = eval(binary.getLeft(), inputs);
        EvalResult right = eval(binary.getRight(), inputs);

        return switch (binary.getOp()) {
            case ADD -> EvalResult.of(left.getComputed() + right.getComputed());
            case SUBTRACT -> EvalResult.of(left.getComputed() - right.getComputed());
            case MULTIPLY -> EvalResult.of(left.getComputed() * right.getComputed());
            case DIVIDE -> {
                if (right.getComputed() == 0.0d) {
                    yield EvalResult.zeroDivisor();
                }
                yield EvalResult.of(left.getComputed() / right.getComputed());
            }
        };
    }
    private String displayOf(Stat stat) {
        String name = stat.name().toLowerCase(Locale.ROOT);
        return switch (name) {
            case "hits" -> "hits";
            case "failures" -> "failures";
            case "failure_rate" -> "failureRate";
            case "qps" -> "qps";
            case "avg" -> "avgDuration";
            default -> name;
        };
    }
    /** 内部求值结果：区分「有值」与「除零」。 */
    @lombok.Getter
    @lombok.EqualsAndHashCode
    @lombok.ToString
    private static class EvalResult {
        private final Double computed;

        private final boolean divideByZero;

        public EvalResult(Double computed, boolean divideByZero) {
            this.computed = computed;
            this.divideByZero = divideByZero;
        }


        static EvalResult of(Double computed) {
            return new EvalResult(computed == null ? 0.0d : computed, false);
        }

        static EvalResult zeroDivisor() {
            return new EvalResult(null, true);
        }
    }
}
