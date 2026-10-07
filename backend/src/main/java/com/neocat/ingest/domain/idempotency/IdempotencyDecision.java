package com.neocat.ingest.domain.idempotency;

/**
 * 幂等判定结果（PRD 02 §6.1）。
 */
@org.springframework.modulith.NamedInterface("tree")
public enum IdempotencyDecision {
    /** 未见过该 messageId，正常接收并处理。 */
    NEW,
    /** 已见过且内容相同：幂等成功，不重复目录、统计、Trace。 */
    DUPLICATE,
    /** 已见过但内容不同：拒绝，并记录 ID 冲突质量异常。 */
    CONFLICT
}
