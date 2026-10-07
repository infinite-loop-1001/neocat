package com.neocat.query.domain.stat;

import org.springframework.modulith.NamedInterface;

import java.util.Locale;

/**
 * 报表统计项（技术方案 03 §4.2）。
 *
 * <p>单位用于大盘公式校验（COUNT / DURATION / RATE）。
 */
@NamedInterface("query")
public enum Stat {
    HITS("Hits", StatUnit.COUNT),
    FAILURES("Failures", StatUnit.COUNT),
    FAILURE_RATE("Failure Rate", StatUnit.RATE),
    QPS("QPS", StatUnit.RATE),
    AVG("Avg Duration", StatUnit.DURATION),
    MIN("Min", StatUnit.DURATION),
    MAX("Max", StatUnit.DURATION),
    TP50("tp50", StatUnit.DURATION),
    TP90("tp90", StatUnit.DURATION),
    TP95("tp95", StatUnit.DURATION),
    TP99("tp99", StatUnit.DURATION),
    TP999("tp999", StatUnit.DURATION),
    TP9999("tp9999", StatUnit.DURATION);

    private final String display;

    private final StatUnit unit;

    Stat(String display, StatUnit unit) {
        this.display = display;
        this.unit = unit;
    }
    public String display() {
        return display;
    }
    public StatUnit unit() {
        return unit;
    }
    public boolean isPercentile() {
        return name().startsWith("TP");
    }
    /** 分位值（0–1）；非分位统计项返回 -1。 */
    public double percentileFraction() {
        if (!isPercentile()) {
            return -1.0d;
        }
        String digits = name().substring(2);
        double denominator = Math.pow(10, digits.length());
        return Double.parseDouble(digits) / denominator;
    }
    /** Event 只支持次数类与 QPS（PRD 03 §8：不提供耗时与分位）。 */
    public boolean applicableToEvent() {
        return unit == StatUnit.COUNT || this == QPS || this == FAILURE_RATE;
    }
    /** 异常类 Problem 只支持次数，不支持分位（PRD 03 §9）。 */
    public boolean applicableToExceptionProblem() {
        return unit == StatUnit.COUNT;
    }
    public static Stat parse(String raw) {
        if (raw == null) {
            throw new IllegalArgumentException("stat 不能为空");
        }
        return Stat.valueOf(raw.trim().toUpperCase(Locale.ROOT));
    }
}
