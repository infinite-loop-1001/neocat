package com.neocat.query.api.http.dto.report;

import lombok.Getter;
import lombok.Setter;
import io.swagger.v3.oas.annotations.media.Schema;

@Schema(name = "ReportRange", description = "解析后的查询窗口")
@Getter
@Setter
public class Range {
    @Schema(description = "窗口起点（epoch millis）")
    private long from;

    @Schema(description = "窗口终点（epoch millis）")
    private long to;
}
