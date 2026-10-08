package com.neocat.trace.api.http.dto;

import lombok.AllArgsConstructor;
import lombok.Getter;
import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;

@Schema(name = "TraceDtos", description = "Trace 响应契约容器")
public final class TraceDtos {
    private TraceDtos() {
    }

    @Schema(name = "TraceTree", description = "单条消息的调用链树")
    @Getter
    @AllArgsConstructor
    public static class TraceResponse {
        @Schema(description = "上报消息 ID")
        private final String messageId;

        @Schema(description = "原始树是否已超过留存期")
        private final boolean expired;

        @Schema(description = "根节点的子节点列表")
        private final List<Node> children;

        @Schema(description = "未落库或上报缺失的节点数")
        private final long missingNodes;

        @Schema(description = "因超过留存期无法组装的节点数")
        private final long expiredNodes;
    }

    @Schema(name = "TraceNode", description = "调用链节点；availability 与 reason 表达可下钻状态")
    @Getter
    @AllArgsConstructor
    public static class Node {
        @Schema(description = "节点消息 ID")
        private final String messageId;

        @Schema(description = "所属服务")
        private final String service;

        @Schema(description = "实例 ID")
        private final String instance;

        @Schema(description = "可用性：PRESENT | MISSING | EXPIRED")
        private final String availability;

        @Schema(description = "缺失或过期的原因说明；正常时为 null", nullable = true)
        private final String reason;

        @Schema(description = "原始树记录时间（epoch millis）；未落库为 null", nullable = true)
        private final Long treeTimestamp;

        @Schema(description = "该节点的 span 列表")
        private final List<Span> spans;

        @Schema(description = "子节点")
        private final List<Node> children;
    }

    @Schema(name = "TraceSpan", description = "节点内的单次调用记录")
    @Getter
    @AllArgsConstructor
    public static class Span {
        @Schema(description = "节点内唯一 ID")
        private final String nodeId;

        @Schema(description = "调用类别，如 TRANSACTION / EVENT / PROBLEM")
        private final String kind;

        @Schema(description = "Problem 细分类；非 Problem 为 null", nullable = true)
        private final String category;

        @Schema(description = "调用名，如 POST /orders")
        private final String name;

        @Schema(description = "状态：ok | fail 等上报值")
        private final String status;

        @Schema(description = "耗时（毫秒）")
        private final long durationMs;

        @Schema(description = "补充明细")
        private final String detail;

        @Schema(description = "事件时间（epoch millis）")
        private final long timestamp;
    }
}
