package com.neocat.query.domain.report;

import java.util.Objects;

/**
 * Type / Name 层的表格行（PRD 03 §7.1、§7.2）。
 *
 * <p>PRD 03 §7.1 要求的字段集合：
 * 总量、失败量、失败率、最小耗时、最大耗时、平均耗时、
 * tp50、tp90、tp95、tp99、tp999、tp9999、CAT 口径 QPS。
 */
@org.springframework.modulith.NamedInterface("query")
@lombok.Getter
@lombok.EqualsAndHashCode
@lombok.ToString
public class ReportRow {
    private final String type;

    private final String name;

    private final long total;

    private final long failures;

    private final Double failureRate;

    private final long minDuration;

    private final long maxDuration;

    private final Double avgDuration;

    private final Double tp50;

    private final Double tp90;

    private final Double tp95;

    private final Double tp99;

    private final Double tp999;

    private final Double tp9999;

    private final Double qps;

    public ReportRow(String type, String name, long total, long failures, Double failureRate, long minDuration, long maxDuration, Double avgDuration, Double tp50, Double tp90, Double tp95, Double tp99, Double tp999, Double tp9999, Double qps) {
        this.type = type;
        this.name = name;
        this.total = total;
        this.failures = failures;
        this.failureRate = failureRate;
        this.minDuration = minDuration;
        this.maxDuration = maxDuration;
        this.avgDuration = avgDuration;
        this.tp50 = tp50;
        this.tp90 = tp90;
        this.tp95 = tp95;
        this.tp99 = tp99;
        this.tp999 = tp999;
        this.tp9999 = tp9999;
        this.qps = qps;
    }

    /** 该行的耗时指标是否可用（Event 无耗时，异常类 Problem 无分位）。 */
    public boolean hasDurationMetrics() {
        return Objects.nonNull(avgDuration) || Objects.nonNull(tp99);
    }
    public boolean hasPercentiles() {
        return Objects.nonNull(tp99);
    }
}











