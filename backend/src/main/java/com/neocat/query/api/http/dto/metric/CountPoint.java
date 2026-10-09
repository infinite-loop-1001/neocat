package com.neocat.query.api.http.dto.metric;

import lombok.Getter;
import lombok.Setter;
import io.swagger.v3.oas.annotations.media.Schema;

@Schema(name = "MetricCountPoint", description = "Metric count 点")
@Getter
@Setter
public class CountPoint {
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
