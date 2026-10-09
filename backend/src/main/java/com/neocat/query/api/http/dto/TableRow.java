package com.neocat.query.api.http.dto;

import java.math.BigDecimal;

import lombok.Getter;
import lombok.Setter;
import io.swagger.v3.oas.annotations.media.Schema;

@Schema(name = "ReportTableRow", description = "Transaction / Event 明细行")
@Getter
@Setter
public class TableRow {
    @Schema(description = "维度 Type，如 URL / SQL")
    private String type;

    @Schema(description = "维度 Name")
    private String name;

    @Schema(description = "调用次数")
    private long total;

    @Schema(description = "失败次数")
    private long failures;

    @Schema(description = "失败率，6 位小数；无耗时观测时为 null", nullable = true)
    private BigDecimal failureRate;

    @Schema(description = "每秒调用数，6 位小数", nullable = true)
    private BigDecimal qps;

    @Schema(description = "耗时最小值（毫秒）；无数据为 null", nullable = true)
    private Long min;

    @Schema(description = "耗时最大值（毫秒）；无数据为 null", nullable = true)
    private Long max;

    @Schema(description = "平均耗时（毫秒），6 位小数；无耗时观测时为 null", nullable = true)
    private BigDecimal avg;

    /**
     * 分位耗时（毫秒）：合并分布后重算，6 位小数；不支持分位时为 null。
     */
    @Schema(description = "TP50 耗时（毫秒），6 位小数", nullable = true)
    private BigDecimal tp50;

    @Schema(description = "TP90 耗时（毫秒），6 位小数", nullable = true)
    private BigDecimal tp90;

    @Schema(description = "TP95 耗时（毫秒），6 位小数", nullable = true)
    private BigDecimal tp95;

    @Schema(description = "TP99 耗时（毫秒），6 位小数", nullable = true)
    private BigDecimal tp99;

    @Schema(description = "TP999 耗时（毫秒），6 位小数", nullable = true)
    private BigDecimal tp999;

    @Schema(description = "TP9999 耗时（毫秒），6 位小数", nullable = true)
    private BigDecimal tp9999;
}
