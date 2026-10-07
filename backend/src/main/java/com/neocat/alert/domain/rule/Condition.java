package com.neocat.alert.domain.rule;

import com.neocat.query.domain.stat.Stat;

import java.util.Objects;

/**
 * 比较条件（PRD 06 §2）。
 *
 * <p>一条规则可含多个条件，但**所有条件都作用于同一目标序列**，
 * 且**连续点数 X 属于整条规则**，不能逐条件配置。
 *
 * @param stat       统计项
 * @param comparator 比较符
 * @param threshold  阈值
 */
@org.springframework.modulith.NamedInterface("alert")
@lombok.Getter
@lombok.EqualsAndHashCode
@lombok.ToString
public class Condition {
    private final Stat stat;

    private final Comparator comparator;

    private final double threshold;

    public Condition(Stat stat, Comparator comparator, double threshold) {
        this.stat = stat;
        this.comparator = comparator;
        this.threshold = threshold;
    }

    /**
     * 判断一个值是否满足本条件。
     *
     * <p>{@code value} 为 {@code null} 表示缺数：**缺数不满足任何条件**，
     * 因为它既不高于也不低于任何阈值（PRD 06 §6）。
     */
    public boolean matches(Double value) {
        if (Objects.isNull(value)) {
            return false;
        }
        return switch (comparator) {
            case GT -> value > threshold;
            case GTE -> value >= threshold;
            case LT -> value < threshold;
            case LTE -> value <= threshold;
            case EQ -> value == threshold;
            case NEQ -> value != threshold;
        };
    }
}
