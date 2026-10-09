package com.neocat.query.api.http.dto;

import java.math.BigDecimal;

import lombok.Getter;
import lombok.Setter;
import io.swagger.v3.oas.annotations.media.Schema;

@Schema(name = "MomPoint", description = "环比点")
@Getter
@Setter
public class MomPoint {
    @Schema(description = "桶起点（epoch millis）")
    private long bucketStart;

    @Schema(description = "同位置的上一周期值，6 位小数", nullable = true)
    private BigDecimal value;
}
