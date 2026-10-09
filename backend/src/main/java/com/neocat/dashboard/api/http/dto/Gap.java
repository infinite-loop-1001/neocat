package com.neocat.dashboard.api.http.dto;

import lombok.AllArgsConstructor;
import lombok.Getter;
import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;

@Schema(name = "CardSeriesGap", description = "缺数桶；missingInputs 指出缺失的公式输入")
@Getter
@AllArgsConstructor
public class Gap {
    @Schema(description = "桶起点（epoch millis）")
    private final long bucketStart;

    @Schema(description = "缺失的输入项名")
    private final List<String> missingInputs;
}
