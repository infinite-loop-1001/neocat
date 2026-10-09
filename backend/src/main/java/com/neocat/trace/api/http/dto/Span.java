package com.neocat.trace.api.http.dto;

import lombok.AllArgsConstructor;
import lombok.Getter;
import io.swagger.v3.oas.annotations.media.Schema;

@Schema(name = "TraceSpan", description = "节点内的单次调用记录")
@Getter
@AllArgsConstructor
public class Span {
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
