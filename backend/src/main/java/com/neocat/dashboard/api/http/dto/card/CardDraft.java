package com.neocat.dashboard.api.http.dto.card;

import lombok.AllArgsConstructor;
import lombok.Getter;
import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;

@Schema(name = "CardDraft", description = "卡片草稿；公式非法或单位混用返回 400")
@Getter
@AllArgsConstructor
public class CardDraft {
    @Schema(description = "服务名", requiredMode = Schema.RequiredMode.REQUIRED)
    private final String service;

    @Schema(description = "目标类型，如 TRANSACTION / EVENT / PROBLEM / METRIC / HEARTBEAT")
    private final String targetKind;

    @Schema(description = "目标维度 Type，如 URL / SQL")
    private final String targetType;

    @Schema(description = "目标维度 Name")
    private final String targetName;

    @Schema(description = "Metric 标签筛选，形如 {\"channel\":[\"app\"]}；非 Metric 为 null", nullable = true)
    private final String metricLabels;

    @Schema(description = "聚合公式，如 sum(hits)、failures / hits", nullable = true)
    private final String formula;

    @Schema(description = "时间范围，如 RECENT_24H / TODAY")
    private final String timeRange;

    @Schema(description = "阈值线；只用于展示，不参与告警判定")
    private final List<Threshold> thresholdLines;
}
