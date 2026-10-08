package com.neocat.ingest.domain.tree;

import java.util.Map;
import java.util.Objects;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.ToString;
import org.springframework.modulith.NamedInterface;

/**
 * MessageTree 内的一个节点（PRD 02 §3、技术方案 04 §2）。
 *
 * @param nodeId     树内唯一
 * @param kind       节点类型
 * @param category   URL / SQL / CALL / CACHE / business / jvm / 指标名 等
 * @param name       节点名称
 * @param status     "0" 表示成功；其他值（含 "ERROR"）为非成功
 * @param timestamp  事件发生时间（epoch millis）——报表时间桶的唯一依据
 * @param durationMs 耗时；仅 Transaction 与 RemoteCall 有意义
 * @param parentNodeId 树内父节点，可空
 */
@NamedInterface("tree")
@Getter
@EqualsAndHashCode
@ToString
public class RawNode {
    private final String nodeId;

    private final NodeKind kind;

    private final String category;

    private final String name;

    private final String status;

    private final long timestamp;

    private final long durationMs;

    private final String parentNodeId;

    private final MetricValue metric;

    private final HeartbeatValue heartbeat;

    private final RemoteCallValue remoteCall;

    private final ExceptionValue exception;

    private final Map<String, String> tags;

    public RawNode(String nodeId, NodeKind kind, String category, String name, String status, long timestamp, long durationMs, String parentNodeId, MetricValue metric, HeartbeatValue heartbeat, RemoteCallValue remoteCall, ExceptionValue exception, Map<String, String> tags) {
        this.nodeId = nodeId;
        this.kind = kind;
        this.category = category;
        this.name = name;
        this.status = status;
        this.timestamp = timestamp;
        this.durationMs = durationMs;
        this.parentNodeId = parentNodeId;
        this.metric = metric;
        this.heartbeat = heartbeat;
        this.remoteCall = remoteCall;
        this.exception = exception;
        this.tags = tags;
    }

    public static final String STATUS_SUCCESS = "0";

    public boolean succeeded() {
        return Objects.equals(STATUS_SUCCESS, status);
    }
}