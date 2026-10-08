package com.neocat.query.api.internal;

import java.math.BigDecimal;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * Null values mean missing measurements, never silently turn them into zero.
 */
public interface ReportPoints {
    /**
     * 后续公式的统计输入；除法七位，不提前做最终六位舍入。
     */
    Map<String, BigDecimal> values(String kind, String service, String type, String name,
                                   Instant from, Instant to, List<String> stats, List<String> instances);
}
