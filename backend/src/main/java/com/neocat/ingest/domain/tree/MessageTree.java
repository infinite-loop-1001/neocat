package com.neocat.ingest.domain.tree;

import java.util.List;
import java.util.Objects;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.ToString;
import org.springframework.modulith.NamedInterface;

/**
 * 一棵本地 MessageTree（PRD 02 §2、技术方案 04 §2）。
 *
 * <p>关键 ID 语义：
 * <ul>
 *   <li>{@code messageId} 全局唯一，是树级幂等键；</li>
 *   <li>{@code rootMessageId} 跨服务 Trace 的根 MessageTree ID；</li>
 *   <li>{@code parentMessageId} 当前树的上游 MessageTree ID；</li>
 *   <li>{@code treeTimestamp} 树内最早节点事件时间，用于迟到判定与 Trace 过期计算。</li>
 * </ul>
 */
@NamedInterface("tree")
@Getter
@EqualsAndHashCode
@ToString
public class MessageTree {
    private final String serviceName;

    private final String instanceId;

    private final String messageId;

    private final String rootMessageId;

    private final String parentMessageId;

    private final long treeTimestamp;

    private final List<RawNode> nodes;

    public MessageTree(String serviceName, String instanceId, String messageId, String rootMessageId, String parentMessageId, long treeTimestamp, List<RawNode> nodes) {
        this.serviceName = serviceName;
        this.instanceId = instanceId;
        this.messageId = messageId;
        this.rootMessageId = rootMessageId;
        this.parentMessageId = parentMessageId;
        this.treeTimestamp = treeTimestamp;
        this.nodes = nodes;
    }

    public boolean hasParent() {
        return Objects.nonNull(parentMessageId) && !parentMessageId.isBlank();
    }
}