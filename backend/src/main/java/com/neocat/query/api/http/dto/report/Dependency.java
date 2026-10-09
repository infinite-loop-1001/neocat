package com.neocat.query.api.http.dto.report;

import java.math.BigDecimal;

import lombok.Getter;
import lombok.Setter;
import io.swagger.v3.oas.annotations.media.Schema;

@Schema(name = "DependencyRow", description = "上游/下游依赖行")
@Getter
@Setter
public class Dependency {
    @Schema(description = "对端服务名")
    private String peer;

    @Schema(description = "调用次数")
    private long calls;

    @Schema(description = "失败率，6 位小数", nullable = true)
    private BigDecimal failureRate;

    @Schema(description = "平均耗时（毫秒），6 位小数", nullable = true)
    private BigDecimal avg;

    @Schema(description = "TP99 耗时（毫秒），6 位小数", nullable = true)
    private BigDecimal tp99;
}
