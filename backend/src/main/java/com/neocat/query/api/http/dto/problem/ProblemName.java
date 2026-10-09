package com.neocat.query.api.http.dto.problem;

import java.math.BigDecimal;

import lombok.Getter;
import lombok.Setter;
import io.swagger.v3.oas.annotations.media.Schema;

@Schema(name = "ProblemNameRow", description = "Problem 分类下的明细行")
@Getter
@Setter
public class ProblemName {
    @Schema(description = "异常名或 Transaction Name")
    private String name;

    @Schema(description = "记录数")
    private long total;

    @Schema(description = "TP99 耗时（毫秒），6 位小数；不支持分位时为 null", nullable = true)
    private BigDecimal tp99;
}
