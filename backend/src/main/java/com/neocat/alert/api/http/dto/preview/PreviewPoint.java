package com.neocat.alert.api.http.dto.preview;

import lombok.AllArgsConstructor;
import lombok.Getter;
import io.swagger.v3.oas.annotations.media.Schema;

@Schema(name = "AlertPreviewPoint", description = "预览的逐点判定；缺数点 known=false")
@Getter
@AllArgsConstructor
public class PreviewPoint {
    @Schema(description = "分钟起点（epoch millis，已回退评估延迟）")
    private final long minute;

    @Schema(description = "该点是否有足够数据参与判定")
    private final boolean known;

    @Schema(description = "该点是否满足全部条件；known=false 时无意义")
    private final boolean satisfied;

    @Schema(description = "缺数时指出缺失的统计项；不缺数为 null", nullable = true)
    private final String missingStat;
}
