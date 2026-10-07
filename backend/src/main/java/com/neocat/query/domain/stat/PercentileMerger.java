package com.neocat.query.domain.stat;

import com.neocat.analysis.domain.bucket.AggregatedRow;
import com.neocat.analysis.domain.bucket.DurationDistribution;

import java.util.List;
import java.util.Objects;

/**
 * 分位合并（PRD 03 §3、PRD 04 §9，技术方案 03 §8.1）。
 *
 * <p>铁律：**合并原始分布后重新计算分位，绝不平均各桶或各机器的分位**。
 * 本类把所有「跨桶 / 跨机器 / 跨层级」的分位需求收敛到一个入口，
 * 避免各处各自实现而产生口径分叉。
 *
 * <p>合并策略由 {@link DurationDistribution#merge} 决定：
 * 只有当所有输入都处于精确模式时，合并结果才保持精确模式（零误差）；
 * 一旦有任一侧已退化为分箱，合并结果也按分箱估算，避免用部分精确值
 * 冒充全局精确值。
 */
@org.springframework.modulith.NamedInterface("query")
public class PercentileMerger {

    /**
     * 合并多行的分布并计算分位。
     *
     * @param rows 输入行（可为单桶多机器，或多桶单机器，或两者的组合）
     * @param p    分位（0–1）
     * @return 分位值；无样本时返回 {@code null}
     */
    public Double percentile(List<AggregatedRow> rows, double p) {
        DurationDistribution merged = mergeDistribution(rows);
        return merged.percentile(p);
    }
    /**
     * 合并多行的分布，返回新的合并分布对象（不修改输入行）。
     */
    public DurationDistribution mergeDistribution(List<AggregatedRow> rows) {
        if (Objects.isNull(rows) || rows.isEmpty()) {
            return new DurationDistribution();
        }
        DurationDistribution merged = null;
        for (AggregatedRow row : rows) {
            DurationDistribution copy = row.distribution().copy();
            merged = Objects.isNull(merged) ? copy : merged.merge(copy);
        }
        return Objects.isNull(merged) ? new DurationDistribution() : merged;
    }
    /** 一次算出的标准分位集合。 */
    public Percentiles percentiles(List<AggregatedRow> rows) {
        DurationDistribution merged = mergeDistribution(rows);
        return new Percentiles(
                merged.percentile(0.50d),
                merged.percentile(0.90d),
                merged.percentile(0.95d),
                merged.percentile(0.99d),
                merged.percentile(0.999d),
                merged.percentile(0.9999d));
    }
    /**
     * 标准分位集合。
     */
    @org.springframework.modulith.NamedInterface("query")
    @lombok.Getter
    @lombok.EqualsAndHashCode
    @lombok.ToString
    public static class Percentiles {
        private final Double tp50;

        private final Double tp90;

        private final Double tp95;

        private final Double tp99;

        private final Double tp999;

        private final Double tp9999;

        public Percentiles(Double tp50, Double tp90, Double tp95, Double tp99, Double tp999, Double tp9999) {
            this.tp50 = tp50;
            this.tp90 = tp90;
            this.tp95 = tp95;
            this.tp99 = tp99;
            this.tp999 = tp999;
            this.tp9999 = tp9999;
        }

    }
}


