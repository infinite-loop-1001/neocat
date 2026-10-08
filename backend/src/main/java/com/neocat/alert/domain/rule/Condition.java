package com.neocat.alert.domain.rule;

import com.neocat.query.domain.stat.Stat;

import java.math.BigDecimal;
import java.util.Objects;

import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.ToString;
import org.springframework.modulith.NamedInterface;

/**
 * 比较条件（PRD 06 §2）。
 *
 * <p>一条规则可含多个条件，但**所有条件都作用于同一目标序列**，
 * 且**连续点数 X 属于整条规则**，不能逐条件配置。
 *
 * <p>统计项、比较符与非空十进制阈值在构造时确定；数值比较与 scale 无关。
 */
@NamedInterface("alert")
@Getter
@EqualsAndHashCode
@ToString
public class Condition {
    private final Stat stat;

    private final Comparator comparator;

    private final BigDecimal threshold;

    public Condition(Stat stat, Comparator comparator, BigDecimal threshold) {
        this.stat = stat;
        this.comparator = comparator;
        this.threshold = Objects.requireNonNull(threshold, "threshold");
        if (threshold.stripTrailingZeros().scale() > 6
                || threshold.abs().compareTo(BigDecimal.TEN.pow(14)) >= 0) {
            throw new IllegalArgumentException("threshold 必须符合 DECIMAL(20,6)，不允许存储时截断");
        }
    }

    /**
     * 判断一个值是否满足本条件。
     *
     * <p>{@code value} 为 {@code null} 表示缺数：**缺数不满足任何条件**，
     * 因为它既不高于也不低于任何阈值（PRD 06 §6）。
     */
    public boolean matches(BigDecimal value) {
        if (Objects.isNull(value)) {
            return false;
        }
        int comparison = value.compareTo(threshold);
        return switch (comparator) {
            case GT -> comparison > 0;
            case GTE -> comparison >= 0;
            case LT -> comparison < 0;
            case LTE -> comparison <= 0;
            case EQ -> comparison == 0;
            case NEQ -> comparison != 0;
        };
    }
}
