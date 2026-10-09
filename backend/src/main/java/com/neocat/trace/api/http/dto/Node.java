package com.neocat.trace.api.http.dto;

import lombok.AllArgsConstructor;
import lombok.Getter;
import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;

@Schema(name = "TraceNode", description = "调用链节点；availability 与 reason 表达可下钻状态")
@Getter
@AllArgsConstructor
public class Node {
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
