package com.neocat.trace.domain.tree;

import java.util.Map;

/**
 * 树内节点（用于 Trace 展示）。
 *
 * @param nodeId     树内唯一
 * @param kind       节点类型名（TRANSACTION / EVENT / …）
 * @param category   分类（URL / SQL / …）
 * @param name       名称
 * @param status     状态；"0" 为成功
 * @param timestamp  事件时间（epoch millis）
 * @param durationMs 耗时
 * @param detail     附加信息（异常消息、SQL 语句等）
 * @param tags       标签
 */
@org.springframework.modulith.NamedInterface("trace")
@lombok.Getter
@lombok.EqualsAndHashCode
@lombok.ToString
public class TraceNode {
    private final String nodeId;

    private final String kind;

    private final String category;

    private final String name;

    private final String status;

    private final long timestamp;

    private final long durationMs;

    private final String detail;

    private final Map<String, String> tags;

    public TraceNode(String nodeId, String kind, String category, String name, String status, long timestamp, long durationMs, String detail, Map<String, String> tags) {
        this.nodeId = nodeId;
        this.kind = kind;
        this.category = category;
        this.name = name;
        this.status = status;
        this.timestamp = timestamp;
        this.durationMs = durationMs;
        this.detail = detail;
        this.tags = tags;
    }

}






