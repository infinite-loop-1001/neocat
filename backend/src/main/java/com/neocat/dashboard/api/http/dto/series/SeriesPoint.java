package com.neocat.dashboard.api.http.dto.series;

import java.math.BigDecimal;

import lombok.AllArgsConstructor;
import lombok.Getter;
import io.swagger.v3.oas.annotations.media.Schema;

@Schema(name = "CardSeriesPoint",
        description = "卡片序列点；缺数与除零的 value 都是 null，原因分别见 gaps 与 isUndefined")
@Getter
@AllArgsConstructor
public class SeriesPoint {
    @Schema(description = "桶起点（epoch millis）")
    private final long bucketStart;

    @Schema(description = "桶终点（epoch millis，不含）")
    private final long bucketEnd;

    @Schema(description = "公式结果，6 位小数；缺数或除零为 null", nullable = true)
    private final BigDecimal value;

    @Schema(description = "该点结果：OK | GAP | DIVIDE_BY_ZERO")
    private final String outcome;
}
