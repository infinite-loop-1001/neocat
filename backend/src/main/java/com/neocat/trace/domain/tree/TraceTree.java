package com.neocat.trace.domain.tree;

import java.util.List;
import java.util.Objects;

/**
 * 一棵本地 MessageTree（含 Trace 关系与树内节点）。
 *
 * @param messageId        树全局唯一 ID，也是幂等键
 * @param rootMessageId    跨服务 Trace 根 MessageTree ID
 * @param parentMessageId  上游树 ID；可为空
 * @param serviceName      服务名
 * @param instanceId       实例 ID
 * @param treeTimestamp    树内最早节点事件时间
 * @param fingerprint      内容指纹（幂等兜底查询用）
 * @param nodes            树内节点
 */
@org.springframework.modulith.NamedInterface("trace")
@lombok.Getter
@lombok.EqualsAndHashCode
@lombok.ToString
public class TraceTree {
    private final String messageId;

    private final String rootMessageId;

    private final String parentMessageId;

    private final String serviceName;

    private final String instanceId;

    private final long treeTimestamp;

    private final String fingerprint;

    private final List<TraceNode> nodes;

    public TraceTree(String messageId, String rootMessageId, String parentMessageId, String serviceName, String instanceId, long treeTimestamp, String fingerprint, List<TraceNode> nodes) {
        this.messageId = messageId;
        this.rootMessageId = rootMessageId;
        this.parentMessageId = parentMessageId;
        this.serviceName = serviceName;
        this.instanceId = instanceId;
        this.treeTimestamp = treeTimestamp;
        this.fingerprint = fingerprint;
        this.nodes = nodes;
    }

    public boolean hasParent() {
        return Objects.nonNull(parentMessageId) && !parentMessageId.isBlank();
    }
}




