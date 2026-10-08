package com.neocat.query.api.http.dto;

import java.math.BigDecimal;

import lombok.Getter;
import lombok.Setter;
import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;

/**
 * 固定字段读模型；保留原契约的 null 与字段存在性。
 *
 * <p>数值字段均为 JSON 数字：计数为整数，耗时/分位/比例/平均值保留 6 位小数（HALF_UP）。
 */
@Schema(name = "ReportDtos", description = "报表读模型请求、响应契约容器")
public final class ReportDtos {
    private ReportDtos() {
    }

    @Schema(name = "ReportTableRow", description = "Transaction / Event 明细行")
    @Getter
    @Setter
    public static class TableRow {
        @Schema(description = "维度 Type，如 URL / SQL")
        private String type;

        @Schema(description = "维度 Name")
        private String name;

        @Schema(description = "调用次数")
        private long total;

        @Schema(description = "失败次数")
        private long failures;

        @Schema(description = "失败率，6 位小数；无耗时观测时为 null", nullable = true)
        private BigDecimal failureRate;

        @Schema(description = "每秒调用数，6 位小数", nullable = true)
        private BigDecimal qps;

        @Schema(description = "耗时最小值（毫秒）；无数据为 null", nullable = true)
        private Long min;

        @Schema(description = "耗时最大值（毫秒）；无数据为 null", nullable = true)
        private Long max;

        @Schema(description = "平均耗时（毫秒），6 位小数；无耗时观测时为 null", nullable = true)
        private BigDecimal avg;

        /** 分位耗时（毫秒）：合并分布后重算，6 位小数；不支持分位时为 null。 */
        @Schema(description = "TP50 耗时（毫秒），6 位小数", nullable = true)
        private BigDecimal tp50;

        @Schema(description = "TP90 耗时（毫秒），6 位小数", nullable = true)
        private BigDecimal tp90;

        @Schema(description = "TP95 耗时（毫秒），6 位小数", nullable = true)
        private BigDecimal tp95;

        @Schema(description = "TP99 耗时（毫秒），6 位小数", nullable = true)
        private BigDecimal tp99;

        @Schema(description = "TP999 耗时（毫秒），6 位小数", nullable = true)
        private BigDecimal tp999;

        @Schema(description = "TP9999 耗时（毫秒），6 位小数", nullable = true)
        private BigDecimal tp9999;
    }

    @Schema(name = "ProblemCategoryRow", description = "Problem 五类汇总行")
    @Getter
    @Setter
    public static class ProblemCategory {
        @Schema(description = "分类：EXCEPTION | SLOW_URL | SLOW_SQL | SLOW_CALL | SLOW_CACHE")
        private String category;

        @Schema(description = "该分类的记录数")
        private long total;

        @Schema(description = "该分类是否支持分位统计；异常不支持")
        private boolean supportsPercentile;
    }

    @Schema(name = "ProblemNameRow", description = "Problem 分类下的明细行")
    @Getter
    @Setter
    public static class ProblemName {
        @Schema(description = "异常名或 Transaction Name")
        private String name;

        @Schema(description = "记录数")
        private long total;

        @Schema(description = "TP99 耗时（毫秒），6 位小数；不支持分位时为 null", nullable = true)
        private BigDecimal tp99;
    }

    @Schema(name = "ReportPoint", description = "趋势点；bucketStart 左闭、bucketEnd 右开")
    @Getter
    @Setter
    public static class Point {
        @Schema(description = "桶起点（epoch millis，按平台时区对齐）")
        private long bucketStart;

        @Schema(description = "桶终点（epoch millis，不含）")
        private long bucketEnd;

        @Schema(description = "统计值；缺数为 null，绝不写 0", nullable = true)
        private BigDecimal value;

        @Schema(description = "质量：OK | ZERO | NO_DATA | DROPPED | PARTIAL | MERGED_OTHER | REALTIME")
        private String quality;

        @Schema(description = "该桶实际覆盖的秒数，用于 QPS 分母")
        private long coveredSeconds;

        @Schema(description = "是否来自当前小时的实时内存报表")
        private boolean realtime;

        @Schema(description = "该桶是否只覆盖了部分时长")
        private boolean partial;
    }

    @Schema(name = "HeartbeatPoint", description = "心跳趋势点；每桶取事件时间最后一次有效采样")
    @Getter
    @Setter
    public static class HeartbeatPoint {
        @Schema(description = "桶起点（epoch millis）")
        private long bucketStart;

        @Schema(description = "桶终点（epoch millis，不含）")
        private long bucketEnd;

        @Schema(description = "最后有效采样值；无采样为 null", nullable = true)
        private BigDecimal value;

        @Schema(description = "质量：OK | ZERO | NO_DATA | DROPPED | PARTIAL | REALTIME")
        private String quality;

        @Schema(description = "该桶实际覆盖的秒数")
        private long coveredSeconds;
    }

    @Schema(name = "ReportRange", description = "解析后的查询窗口")
    @Getter
    @Setter
    public static class Range {
        @Schema(description = "窗口起点（epoch millis）")
        private long from;

        @Schema(description = "窗口终点（epoch millis）")
        private long to;
    }

    @Schema(name = "MomPoint", description = "环比点")
    @Getter
    @Setter
    public static class MomPoint {
        @Schema(description = "桶起点（epoch millis）")
        private long bucketStart;

        @Schema(description = "同位置的上一周期值，6 位小数", nullable = true)
        private BigDecimal value;
    }

    @Schema(name = "Mom", description = "环比序列；kind 为 DAY | WEEK | MONTH")
    @Getter
    @Setter
    public static class Mom {
        @Schema(description = "环比周期：DAY | WEEK | MONTH")
        private String kind;

        @Schema(description = "对齐后的上一周期点")
        private List<MomPoint> points;
    }

    @Schema(name = "ReportSeries", description = "统一趋势响应；points 与 mom 对齐到同一桶序列")
    @Getter
    @Setter
    public static class Series {
        @Schema(description = "服务名")
        private String service;

        @Schema(description = "报表类型")
        private String kind;

        @Schema(description = "维度 Type")
        private String type;

        @Schema(description = "维度 Name")
        private String name;

        @Schema(description = "统计项")
        private String stat;

        @Schema(description = "单位：COUNT | DURATION | RATE | NUMBER")
        private String unit;

        @Schema(description = "桶粒度（秒）")
        private long bucketSeconds;

        @Schema(description = "解析后的查询窗口")
        private Range range;

        @Schema(description = "趋势点；缺数点为 null 值并带 quality")
        private List<Point> points;

        @Schema(description = "环比序列；未请求时为 null", nullable = true)
        private Mom mom;
    }

    @Schema(name = "HeartbeatInstanceValue", description = "心跳实例及其最后值")
    @Getter
    @Setter
    public static class HeartbeatInstance {
        @Schema(description = "实例 ID")
        private String instance;

        @Schema(description = "窗口内最后一次有效采样值；无采样为 null", nullable = true)
        private BigDecimal value;
    }

    @Schema(name = "HeartbeatInstanceSeries", description = "单实例的心跳序列")
    @Getter
    @Setter
    public static class InstanceSeries {
        @Schema(description = "实例 ID")
        private String instance;

        @Schema(description = "该实例的趋势点")
        private List<HeartbeatPoint> points;
    }

    @Schema(name = "HeartbeatSeries", description = "心跳趋势；不合并不同 JVM 的值")
    @Getter
    @Setter
    public static class HeartbeatSeries {
        @Schema(description = "服务名")
        private String service;

        @Schema(description = "JVM 指标 key")
        private String metric;

        @Schema(description = "桶粒度（秒）")
        private long bucketSeconds;

        @Schema(description = "按实例分组的序列")
        private List<InstanceSeries> series;

        @Schema(description = "环比序列；一期心跳不支持，恒为 null", nullable = true)
        private Mom mom;
    }

    @Schema(name = "MetricRankRow", description = "Metric 排名行；other 为跨小时合并项")
    @Getter
    @Setter
    public static class MetricRank {
        @Schema(description = "标签组合的展示文本；other 为合并项")
        private String labels;

        @Schema(description = "该组合在小时内的上报次数")
        private long reportCount;

        @Schema(description = "名次，从 1 开始")
        private int rank;
    }

    @Schema(name = "DependencyRow", description = "上游/下游依赖行")
    @Getter
    @Setter
    public static class Dependency {
        @Schema(description = "对端服务名")
        private String peer;

        @Schema(description = "调用次数")
        private long calls;

        @Schema(description = "失败率，6 位小数", nullable = true)
        private BigDecimal failureRate;

        @Schema(description = "平均耗时（毫秒），6 位小数", nullable = true)
        private BigDecimal avg;

        @Schema(description = "TP99 耗时（毫秒），6 位小数", nullable = true)
        private BigDecimal tp99;
    }

    @Schema(name = "ReportSample", description = "取样行；traceAvailable 表示原始树是否仍可下钻")
    @Getter
    @Setter
    public static class Sample {
        @Schema(description = "上报消息 ID")
        private String messageId;

        @Schema(description = "事件时间（epoch millis）")
        private long timestamp;

        @Schema(description = "耗时（毫秒）")
        private long durationMs;

        @Schema(description = "状态：ok | fail 等上报值")
        private String status;

        @Schema(description = "请求摘要")
        private String summary;

        @Schema(description = "原始树是否仍在留存期内")
        private boolean traceAvailable;
    }

    @Schema(name = "MetricName", description = "指标名")
    @Getter
    @Setter
    public static class MetricName {
        @Schema(description = "指标名")
        private String name;
    }

    @Schema(name = "MetricLabel", description = "指标标签键及其候选值")
    @Getter
    @Setter
    public static class MetricLabel {
        @Schema(description = "标签键")
        private String key;

        @Schema(description = "该键在范围内出现过的取值")
        private List<String> values;
    }

    @Schema(name = "MetricCount", description = "单指标上报次数曲线")
    @Getter
    @Setter
    public static class MetricCount {
        @Schema(description = "指标名")
        private String metric;

        @Schema(description = "桶粒度（秒）")
        private long bucketSeconds;

        @Schema(description = "count 点；确认无匹配为 0，无法还原合并项为 null")
        private List<CountPoint> points;
    }

    @Schema(name = "MetricCountPoint", description = "Metric count 点")
    @Getter
    @Setter
    public static class CountPoint {
        @Schema(description = "桶起点（epoch millis）")
        private long bucketStart;

        @Schema(description = "桶终点（epoch millis，不含）")
        private long bucketEnd;

        @Schema(description = "上报次数；ZERO 为 0，MERGED_OTHER 等为 null", nullable = true)
        private Long value;

        @Schema(description = "质量：OK | ZERO | NO_DATA | DROPPED | PARTIAL | MERGED_OTHER | REALTIME")
        private String quality;

        @Schema(description = "该桶实际覆盖的秒数")
        private long coveredSeconds;

        @Schema(description = "是否来自当前小时的实时内存报表")
        private boolean realtime;

        @Schema(description = "该桶是否只覆盖了部分时长")
        private boolean partial;
    }
}
