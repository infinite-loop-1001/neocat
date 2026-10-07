package com.neocat.trace.domain.tree;

import com.google.common.collect.Maps;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** 测试数据构造器。 */
public final class TraceFixtures {

    private TraceFixtures() {
    }

    public static TraceNode span(String nodeId, String category, String name,
                                 String status, long durationMs, long timestamp) {
        return new TraceNode(nodeId, "TRANSACTION", category, name, status, timestamp, durationMs, null, Maps.newHashMap());
    }

    /** 一个普通树：单个 URL span。 */
    public static TraceTree tree(String messageId, String rootMessageId, String parentMessageId,
                                 String serviceName, Instant at) {
        return tree(messageId, rootMessageId, parentMessageId, serviceName, at, serviceName + "-ip");
    }

    public static TraceTree tree(String messageId, String rootMessageId, String parentMessageId,
                                 String serviceName, Instant at, String instanceId) {
        return new TraceTree(messageId, rootMessageId, parentMessageId, serviceName, instanceId,
                at.toEpochMilli(), "fp-" + messageId,
                List.of(span("n-1", "URL", "/api", "0", 10L, at.toEpochMilli())));
    }

    public static TraceTree treeAt(String messageId, String rootMessageId, String parentMessageId,
                                   String serviceName, Instant at) {
        return tree(messageId, rootMessageId, parentMessageId, serviceName, at);
    }

    /** 含指定数量 span 的树。 */
    public static TraceTree treeWithSpans(String messageId, String rootMessageId, String parentMessageId,
                                          String serviceName, Instant at, int spanCount) {
        List<TraceNode> spans = new ArrayList<>();
        for (int i = 1; i <= spanCount; i++) {
            spans.add(span("n-" + i, "URL", "/api/" + i, "0", 10L + i, at.toEpochMilli()));
        }
        return new TraceTree(messageId, rootMessageId, parentMessageId, serviceName, serviceName + "-ip",
                at.toEpochMilli(), "fp-" + messageId, spans);
    }

    /** 一棵记录了指向下游服务的 CALL span、但下游树未上报的树。 */
    public static TraceTree treeWithRemoteCallTo(String messageId, String rootMessageId, String parentMessageId,
                                                 String serviceName, String downstream, Instant at) {
        return treeWithRemoteCall(messageId, rootMessageId, parentMessageId, serviceName, downstream,
                at.toEpochMilli(), 50L, 1);
    }

    /**
     * 含 CALL span 的树。
     *
     * @param callStartMs  CALL 开始时间（近似 epoch millis）
     * @param callOffset   在开始时间上的微调，避免每个用例写完整时间戳
     */
    public static TraceTree treeWithRemoteCall(String messageId, String rootMessageId, String parentMessageId,
                                               String serviceName, String downstream,
                                               long callStartMs, long durationMs, long callOffset) {
        TraceNode call = new TraceNode("n-call", "REMOTE_CALL", "CALL", downstream, "0",
                callStartMs + callOffset, durationMs, null, Maps.newHashMap());
        TraceNode url = span("n-1", "URL", "/api", "0", 100L, callStartMs);
        return new TraceTree(messageId, rootMessageId, parentMessageId, serviceName, serviceName + "-ip",
                callStartMs, "fp-" + messageId, List.of(url, call));
    }
}
