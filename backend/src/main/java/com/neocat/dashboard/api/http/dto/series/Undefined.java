package com.neocat.dashboard.api.http.dto.series;

import lombok.AllArgsConstructor;
import lombok.Getter;
import io.swagger.v3.oas.annotations.media.Schema;

@Schema(name = "CardSeriesUndefined", description = "未定义桶；一期只有除零")
@Getter
@AllArgsConstructor
public class Undefined {
    @Schema(description = "桶起点（epoch millis）")
    private final long bucketStart;

    @Schema(description = "原因：DIVIDE_BY_ZERO")
    private final String reason;
}
