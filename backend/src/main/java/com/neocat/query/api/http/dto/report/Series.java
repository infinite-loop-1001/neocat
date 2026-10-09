package com.neocat.query.api.http.dto.report;

import lombok.Getter;
import lombok.Setter;
import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;

@Schema(name = "ReportSeries", description = "统一趋势响应；points 与 mom 对齐到同一桶序列")
@Getter
@Setter
public class Series {
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
