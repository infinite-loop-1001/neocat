package com.neocat.query.domain.report;

import java.util.Objects;

/**
 * 机器维度的一行（PRD 03 §7.3）。
 *
 * @param instance 实例 ID；{@code other} 行为固定标识
 * @param total    总量
 * @param failures 失败量
 * @param avgDuration 平均耗时
 * @param tp99     分位
 * @param qps      QPS
 * @param contribution 当前统计项的贡献值（用于排序，与 stat 相关）
 */
@org.springframework.modulith.NamedInterface("query")
@lombok.Getter
@lombok.EqualsAndHashCode
@lombok.ToString
public class MachineRow {
    private final String instance;

    private final long total;

    private final long failures;

    private final Double avgDuration;

    private final Double tp99;

    private final Double qps;

    private final Double contribution;

    public MachineRow(String instance, long total, long failures, Double avgDuration, Double tp99, Double qps, Double contribution) {
        this.instance = instance;
        this.total = total;
        this.failures = failures;
        this.avgDuration = avgDuration;
        this.tp99 = tp99;
        this.qps = qps;
        this.contribution = contribution;
    }

    /** Top N 之外合并行的实例标识。 */
    public static final String OTHER = "other";

    public boolean isOther() {
        return Objects.equals(OTHER, instance);
    }
}




