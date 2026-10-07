package com.neocat.trace.domain.tree;

import java.util.Objects;

/**
 * Trace 关系记录（技术方案 06 §6）。
 *
 * <p>关系索引与原始树分开存储：原始树有 7 天 TTL，关系记录用于在树被清理后
 * 仍然知道「这个 messageId 曾经存在、它属于哪条 Trace、它的父子是谁」。
 * 这正是区分 {@link NodeAvailability#MISSING}（从未收到）
 * 与 {@link NodeAvailability#EXPIRED}（曾收到但已过期）的依据。
 *
 * @param messageId       树全局唯一 ID
 * @param rootMessageId   所属 Trace 根
 * @param parentMessageId 上游树 ID
 * @param serviceName     服务名
 * @param instanceId      实例 ID
 * @param treeTimestamp   树事件时间（用于过期计算）
 */
@org.springframework.modulith.NamedInterface("trace")
@lombok.Getter
@lombok.EqualsAndHashCode
@lombok.ToString
public class TraceRelation {
    private final String messageId;

    private final String rootMessageId;

    private final String parentMessageId;

    private final String serviceName;

    private final String instanceId;

    private final long treeTimestamp;

    public TraceRelation(String messageId, String rootMessageId, String parentMessageId, String serviceName, String instanceId, long treeTimestamp) {
        this.messageId = messageId;
        this.rootMessageId = rootMessageId;
        this.parentMessageId = parentMessageId;
        this.serviceName = serviceName;
        this.instanceId = instanceId;
        this.treeTimestamp = treeTimestamp;
    }

    public boolean hasParent() {
        return Objects.nonNull(parentMessageId) && !parentMessageId.isBlank();
    }
}


