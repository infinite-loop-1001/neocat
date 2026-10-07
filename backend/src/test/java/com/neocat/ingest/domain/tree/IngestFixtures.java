package com.neocat.ingest.domain.tree;

import com.google.common.collect.Maps;

import java.util.List;
import java.util.Map;

/** 测试数据构造器：让规格中的树构造保持可读。 */
public final class IngestFixtures {

    private IngestFixtures() {
    }

    public static RawNode node(String nodeId, String category, String name,
                               String status, long durationMs, long timestamp) {
        return new RawNode(nodeId, NodeKind.TRANSACTION, category, name, status, timestamp, durationMs,
                null, null, null, null, null, Maps.newHashMap());
    }

    public static MessageTree treeWith(String messageId, String serviceName, String instanceId,
                                       String rootMessageId, String parentMessageId,
                                       long treeTimestamp, List<RawNode> nodes) {
        return new MessageTree(serviceName, instanceId, messageId, rootMessageId, parentMessageId,
                treeTimestamp, nodes);
    }

    public static MessageTree simpleTree(String messageId) {
        return treeWith(messageId, "order", "10.0.0.8", messageId, null, 1790000000000L,
                List.of(node("n-1", "URL", "POST /orders", "0", 12L, 1790000000000L)));
    }

    public static MessageTree metricTree(String messageId, Map<String, String> labels) {
        MetricValue metric = new MetricValue("order.amount", 128.5d, labels);
        RawNode metricNode = new RawNode("n-1", NodeKind.METRIC, "order.amount", "order.amount", "0",
                1790000000000L, 0L, null, metric, null, null, null, Maps.newHashMap());
        return treeWith(messageId, "order", "10.0.0.8", messageId, null, 1790000000000L, List.of(metricNode));
    }
}
