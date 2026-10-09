package com.neocat.query.api.http.dto.report;

import java.math.BigDecimal;

import lombok.Getter;
import lombok.Setter;
import io.swagger.v3.oas.annotations.media.Schema;

@Schema(name = "ReportPoint", description = "趋势点；bucketStart 左闭、bucketEnd 右开")
@Getter
@Setter
public class Point {
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
