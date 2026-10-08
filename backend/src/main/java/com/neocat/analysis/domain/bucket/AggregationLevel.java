package com.neocat.analysis.domain.bucket;

import org.springframework.modulith.NamedInterface;

/**
 * 聚合层级（PRD 03 §2.1、技术方案 06 §3）。
 */
@NamedInterface("analysis")
public enum AggregationLevel {
    MINUTE,
    HOUR,
    DAY,
    WEEK,
    MONTH
}
