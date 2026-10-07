package com.neocat.analysis.domain.analyzer;

import com.google.common.collect.Maps;
import com.neocat.ingest.domain.tree.ExceptionValue;
import com.neocat.ingest.domain.tree.HeartbeatValue;
import com.neocat.ingest.domain.tree.MessageTree;
import com.neocat.ingest.domain.tree.MetricValue;
import com.neocat.ingest.domain.tree.NodeKind;
import com.neocat.ingest.domain.tree.RawNode;
import com.neocat.ingest.domain.tree.RemoteCallValue;

import java.util.List;
import java.util.Map;

/** 测试数据构造器：按分析域构造树，避免规格中重复样板。 */
public final class AnalysisFixtures {

    private AnalysisFixtures() {
    }

    public static RawNode node(String nodeId, String category, String name,
                               String status, long durationMs, long timestamp) {
        return new RawNode(nodeId, NodeKind.TRANSACTION, category, name, status, timestamp, durationMs,
                null, null, null, null, null, Maps.newHashMap());
    }

    public static MessageTree treeWithTimes(String service, String instance, long treeTimestamp, List<RawNode> nodes) {
        String messageId = service + "-" + treeTimestamp + "-" + nodes.size();
        return new MessageTree(service, instance, messageId, messageId, null, treeTimestamp, nodes);
    }

    public static MessageTree tree(String service, String instance, long timestamp,
                                   String category, String name, String status, long durationMs) {
        return treeWithTimes(service, instance, timestamp,
                List.of(node("n-1", category, name, status, durationMs, timestamp)));
    }

    public static MessageTree eventTree(String service, String instance, long timestamp,
                                        String category, String name, String status) {
        RawNode eventNode = new RawNode("n-1", NodeKind.EVENT, category, name, status, timestamp, 0L,
                null, null, null, null, null, Maps.newHashMap());
        return treeWithTimes(service, instance, timestamp, List.of(eventNode));
    }

    public static MessageTree metricTree(String service, String instance, long timestamp,
                                         String metricName, double value) {
        return metricTree(service, instance, timestamp, metricName, value, Maps.newHashMap());
    }

    public static MessageTree metricTree(String service, String instance, long timestamp,
                                         String metricName, double value, Map<String, String> labels) {
        MetricValue metric = new MetricValue(metricName, value, labels);
        RawNode metricNode = new RawNode("n-1", NodeKind.METRIC, metricName, metricName, "0", timestamp, 0L,
                null, metric, null, null, null, Maps.newHashMap());
        return treeWithTimes(service, instance, timestamp, List.of(metricNode));
    }

    public static MessageTree heartbeatTree(String service, String instance, long timestamp) {
        HeartbeatValue hb = new HeartbeatValue(512_000_000L, 2_048_000_000L, 12L, 340L, 96L);
        RawNode hbNode = new RawNode("n-1", NodeKind.HEARTBEAT, "jvm", "jvm", "0", timestamp, 0L,
                null, null, hb, null, null, Maps.newHashMap());
        return treeWithTimes(service, instance, timestamp, List.of(hbNode));
    }

    /** 上游服务对下游发起一次远程调用。 */
    public static MessageTree remoteCallTree(String service, String instance, long timestamp,
                                             String downstream, String callType, String status, long durationMs) {
        RemoteCallValue call = new RemoteCallValue(downstream, "10.9.9.9", callType, status);
        RawNode callNode = new RawNode("n-1", NodeKind.REMOTE_CALL, callType, downstream, status, timestamp,
                durationMs, null, null, null, call, null, Maps.newHashMap());
        return treeWithTimes(service, instance, timestamp, List.of(callNode));
    }

    /** 带异常的失败 Transaction。 */
    public static MessageTree exceptionTree(String service, String instance, long timestamp,
                                            String category, String name, String exceptionName,
                                            String exceptionMessage, long durationMs) {
        ExceptionValue ex = new ExceptionValue(exceptionName, exceptionMessage, "at ...");
        RawNode node = new RawNode("n-1", NodeKind.TRANSACTION, category, name, "ERROR", timestamp,
                durationMs, null, null, null, null, ex, Maps.newHashMap());
        return treeWithTimes(service, instance, timestamp, List.of(node));
    }
}
