package com.neocat.alert.api.http.dto;

import java.math.BigDecimal;

import lombok.AllArgsConstructor;
import lombok.Getter;
import io.swagger.v3.oas.annotations.media.Schema;

@Schema(name = "AlertConditionDraft", description = "单条比较条件")
@Getter
@AllArgsConstructor
public class ConditionDraft {
    @Schema(description = "统计项：HITS | FAILURES | FAILURE_RATE | QPS | AVG | MIN | MAX | TP50 等",
            requiredMode = Schema.RequiredMode.REQUIRED)
    private final String stat;

    @Schema(description = "比较符：GT | GTE | LT | LTE | EQ | NEQ", requiredMode = Schema.RequiredMode.REQUIRED)
    private final String comparator;

    @Schema(description = "阈值，必须显式非空：绝对值小于 10^14 且有效小数不超过 6 位，"
            + "否则返回 400 INVALID_PARAM；比较按数值而非 scale",
            requiredMode = Schema.RequiredMode.REQUIRED, example = "0.05")
    private final BigDecimal threshold;
}
