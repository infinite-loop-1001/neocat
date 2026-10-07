package com.neocat.ingest.domain.receive;

/**
 * 数据质量事件类型（PRD 02 §7、§8；技术方案 03 §4 / 06 §7）。
 *
 * <p>用于记录 EXPIRED / ID_CONFLICT / QUEUE_FULL / MALFORMED / DOMAIN_FAILURE。
 */
@org.springframework.modulith.NamedInterface("tree")
public enum QualityType {
    EXPIRED,
    ID_CONFLICT,
    QUEUE_FULL,
    MALFORMED,
    DOMAIN_FAILURE
}
