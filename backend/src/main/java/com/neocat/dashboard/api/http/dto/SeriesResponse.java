package com.neocat.dashboard.api.http.dto;

import lombok.AllArgsConstructor;
import lombok.Getter;
import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;

@Schema(name = "CardSeriesResponse", description = "卡片序列；gaps 与 isUndefined 恒为数组，没有内容时返回 []")
@Getter
@AllArgsConstructor
public class SeriesResponse {
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
