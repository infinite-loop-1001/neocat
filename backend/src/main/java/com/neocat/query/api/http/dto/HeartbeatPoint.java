package com.neocat.query.api.http.dto;

import java.math.BigDecimal;

import lombok.Getter;
import lombok.Setter;
import io.swagger.v3.oas.annotations.media.Schema;

@Schema(name = "HeartbeatPoint", description = "心跳趋势点；每桶取事件时间最后一次有效采样")
@Getter
@Setter
public class HeartbeatPoint {
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
