package com.neocat.dashboard.api.http.dto;

import java.math.BigDecimal;

import lombok.AllArgsConstructor;
import lombok.Getter;
import io.swagger.v3.oas.annotations.media.Schema;

@Schema(name = "CardThreshold", description = "卡片阈值线；仅展示")
@Getter
@AllArgsConstructor
public class Threshold {
    @Schema(description = "方向：ABOVE | BELOW")
    private final String direction;

    @Schema(description = "阈值，至多 6 位小数", example = "200")
    private final BigDecimal value;
}
