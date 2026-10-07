package com.neocat.analysis.domain.bucket;

import java.time.Instant;
import java.util.List;

/**
 * 聚合层级（PRD 03 §2.1、技术方案 06 §3）。
 */
@org.springframework.modulith.NamedInterface("analysis")
public enum AggregationLevel {
    MINUTE,
    HOUR,
    DAY,
    WEEK,
    MONTH
}
