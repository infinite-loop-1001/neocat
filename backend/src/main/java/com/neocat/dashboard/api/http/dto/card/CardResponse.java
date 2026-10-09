package com.neocat.dashboard.api.http.dto.card;

import lombok.AllArgsConstructor;
import lombok.Getter;
import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;

@Schema(name = "CardResponse", description = "卡片")
@Getter
@AllArgsConstructor
public class CardResponse {
    @Schema(description = "卡片 ID")
    private final long id;

    @Schema(description = "所属大盘 ID")
    private final long dashboardId;

    @Schema(description = "服务名")
    private final String service;

    @Schema(description = "目标类型")
    private final String targetKind;

    @Schema(description = "目标维度 Type")
    private final String targetType;

    @Schema(description = "目标维度 Name")
    private final String targetName;

    @Schema(description = "Metric 标签筛选原文", nullable = true)
    private final String metricLabels;

    @Schema(description = "聚合公式")
    private final String formula;

    @Schema(description = "时间范围")
    private final String timeRange;

    @Schema(description = "阈值线")
    private final List<Threshold> thresholdLines;

    @Schema(description = "单位：COUNT | DURATION | RATE | NUMBER")
    private final String unit;
}
