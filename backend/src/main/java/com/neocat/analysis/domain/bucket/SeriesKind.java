package com.neocat.analysis.domain.bucket;
import org.springframework.modulith.NamedInterface;

/**
 * 报表类型（与 catalog 的 ReportKind 语义一致，analysis 内独立定义以保持模块边界）。
 */
@NamedInterface("analysis")
public enum SeriesKind {
    TRANSACTION,
    EVENT,
    PROBLEM,
    HEARTBEAT,
    METRIC,
    DEPENDENCY
}
