package com.neocat.trace.domain.tree;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import org.springframework.modulith.NamedInterface;

/**
 * 组装后的跨服务调用树节点（PRD 02 §10）。
 *
 * <p>{@code availability} 与 {@code missingReason} 共同表达缺失语义：
 * 已知父子关系但子树未收到 → MISSING；子树曾收到但已过期 → EXPIRED。
 */
@NamedInterface("trace")
public class TraceTreeNode {

    private final String messageId;

    private final String serviceName;

    private final String instanceId;

    private final long treeTimestamp;

    private final NodeAvailability availability;

    private final String missingReason;

    private final List<TraceNode> spans;

    private final List<TraceTreeNode> children;

    public TraceTreeNode(String messageId, String serviceName, String instanceId,
                         long treeTimestamp, NodeAvailability availability, String missingReason) {
        this.messageId = messageId;
        this.serviceName = serviceName;
        this.instanceId = instanceId;
        this.treeTimestamp = treeTimestamp;
        this.availability = availability;
        this.missingReason = missingReason;
        this.spans = new ArrayList<>();
        this.children = new ArrayList<>();
    }
    public String messageId() {
        return messageId;
    }
    public String serviceName() {
        return serviceName;
    }
    public String instanceId() {
        return instanceId;
    }
    public long treeTimestamp() {
        return treeTimestamp;
    }
    public NodeAvailability availability() {
        return availability;
    }
    public String missingReason() {
        return missingReason;
    }
    public List<TraceNode> spans() {
        return spans;
    }
    public List<TraceTreeNode> children() {
        return children;
    }
    public void addSpan(TraceNode span) {
        spans.add(span);
    }
    public void addChild(TraceTreeNode child) {
        children.add(child);
    }
    public boolean present() {
        return Objects.equals(availability, NodeAvailability.PRESENT);
    }
    /** 递归统计缺失节点数。 */
    public long countMissing() {
        long self = Objects.equals(availability, NodeAvailability.MISSING) ? 1 : 0;
        return self + children.stream().mapToLong(TraceTreeNode::countMissing).sum();
    }
    /** 递归统计过期节点数。 */
    public long countExpired() {
        long self = Objects.equals(availability, NodeAvailability.EXPIRED) ? 1 : 0;
        return self + children.stream().mapToLong(TraceTreeNode::countExpired).sum();
    }
}