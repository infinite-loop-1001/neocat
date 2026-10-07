package com.neocat.catalog.domain.report;

/**
 * 报表类型（PRD 03 §1）：服务/实例列表按当前报表类型过滤。
 */
@org.springframework.modulith.NamedInterface("catalog")
public enum ReportKind {
    TRANSACTION,
    EVENT,
    PROBLEM,
    METRIC,
    HEARTBEAT,
    DEPENDENCY
}
