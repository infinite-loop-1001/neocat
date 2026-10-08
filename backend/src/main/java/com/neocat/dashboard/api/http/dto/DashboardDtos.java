package com.neocat.dashboard.api.http.dto;

import java.math.BigDecimal;

import lombok.AllArgsConstructor;
import lombok.Getter;
import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;

@Schema(name = "DashboardDtos", description = "大盘与卡片请求、响应契约容器")
public final class DashboardDtos {
    private DashboardDtos() {
    }

    @Schema(name = "DashboardDraft", description = "大盘草稿")
    @Getter
    @AllArgsConstructor
    public static class DashboardDraft {
        @Schema(description = "所属叶子组织 ID", requiredMode = Schema.RequiredMode.REQUIRED)
        private final long orgId;

        @Schema(description = "大盘名称", requiredMode = Schema.RequiredMode.REQUIRED)
        private final String name;
    }

    @Schema(name = "CardDraft", description = "卡片草稿；公式非法或单位混用返回 400")
    @Getter
    @AllArgsConstructor
    public static class CardDraft {
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

    @Schema(name = "DashboardResponse", description = "大盘")
    @Getter
    @AllArgsConstructor
    public static class DashboardResponse {
        @Schema(description = "大盘 ID")
        private final long id;

        @Schema(description = "所属叶子组织 ID")
        private final long orgId;

        @Schema(description = "大盘名称")
        private final String name;
    }

    @Schema(name = "CardThreshold", description = "卡片阈值线；仅展示")
    @Getter
    @AllArgsConstructor
    public static class Threshold {
        @Schema(description = "方向：ABOVE | BELOW")
        private final String direction;

        @Schema(description = "阈值，至多 6 位小数", example = "200")
        private final BigDecimal value;
    }

    @Schema(name = "CardResponse", description = "卡片")
    @Getter
    @AllArgsConstructor
    public static class CardResponse {
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

    @Schema(name = "AlertableTargetResponse", description = "组织告警可选目标；kind 区分卡片结果与原始统计项")
    @Getter
    @AllArgsConstructor
    public static class TargetResponse {
        @Schema(description = "目标类型：CARD_RESULT | STAT")
        private final String kind;

        @Schema(description = "卡片 ID；STAT 目标为 0")
        private final long cardId;

        @Schema(description = "服务名")
        private final String service;

        @Schema(description = "目标类型，如 TRANSACTION")
        private final String targetKind;

        @Schema(description = "目标维度 Type")
        private final String targetType;

        @Schema(description = "目标维度 Name")
        private final String targetName;

        @Schema(description = "该目标可用的统计项")
        private final List<String> stats;
    }

    @Schema(name = "CardSeriesPoint",
            description = "卡片序列点；缺数与除零的 value 都是 null，原因分别见 gaps 与 isUndefined")
    @Getter
    @AllArgsConstructor
    public static class SeriesPoint {
        @Schema(description = "桶起点（epoch millis）")
        private final long bucketStart;

        @Schema(description = "桶终点（epoch millis，不含）")
        private final long bucketEnd;

        @Schema(description = "公式结果，6 位小数；缺数或除零为 null", nullable = true)
        private final BigDecimal value;

        @Schema(description = "该点结果：OK | GAP | DIVIDE_BY_ZERO")
        private final String outcome;
    }

    @Schema(name = "CardSeriesGap", description = "缺数桶；missingInputs 指出缺失的公式输入")
    @Getter
    @AllArgsConstructor
    public static class Gap {
        @Schema(description = "桶起点（epoch millis）")
        private final long bucketStart;

        @Schema(description = "缺失的输入项名")
        private final List<String> missingInputs;
    }

    @Schema(name = "CardSeriesUndefined", description = "未定义桶；一期只有除零")
    @Getter
    @AllArgsConstructor
    public static class Undefined {
        @Schema(description = "桶起点（epoch millis）")
        private final long bucketStart;

        @Schema(description = "原因：DIVIDE_BY_ZERO")
        private final String reason;
    }

    @Schema(name = "CardSeriesResponse", description = "卡片序列；gaps 与 isUndefined 恒为数组，没有内容时返回 []")
    @Getter
    @AllArgsConstructor
    public static class SeriesResponse {
        @Schema(description = "卡片 ID")
        private final long cardId;

        @Schema(description = "卡片公式")
        private final String formula;

        @Schema(description = "单位：COUNT | DURATION | RATE | NUMBER")
        private final String unit;

        @Schema(description = "阈值线")
        private final List<Threshold> thresholdLines;

        @Schema(description = "序列点")
        private final List<SeriesPoint> points;

        @Schema(description = "缺数桶")
        private final List<Gap> gaps;

        @Schema(description = "除零桶；没有除零点时为 []")
        private final List<Undefined> isUndefined;
    }

    @Schema(name = "DashboardSuccess", description = "无数据体的成功响应")
    @Getter
    @AllArgsConstructor
    public static class Success {
        @Schema(description = "固定为 true")
        private final boolean ok;
    }
}
