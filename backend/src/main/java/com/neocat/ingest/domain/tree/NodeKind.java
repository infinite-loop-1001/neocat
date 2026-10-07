package com.neocat.ingest.domain.tree;

/**
 * 上报节点类型（PRD 02 §3、技术方案 04 §3.2）。
 */
@org.springframework.modulith.NamedInterface("tree")
public enum NodeKind {
    TRANSACTION,
    EVENT,
    HEARTBEAT,
    METRIC,
    REMOTE_CALL
}
