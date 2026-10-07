package com.neocat.ingest.domain.receive;

/**
 * 上报接收结果状态（技术方案 04 §5）。
 *
 * <p>{@link #ACCEPTED} 只表示平台接受了处理尝试，**不表示报表已完成**（PRD 02 §4）。
 */
@org.springframework.modulith.NamedInterface("tree")
public enum IngestStatus {
    ACCEPTED,
    DUPLICATE,
    DROPPED,
    REJECTED
}
