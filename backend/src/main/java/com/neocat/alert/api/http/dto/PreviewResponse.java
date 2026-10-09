package com.neocat.alert.api.http.dto;

import lombok.AllArgsConstructor;
import lombok.Getter;
import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;

@Schema(name = "AlertPreviewResponse", description = "告警预览结果")
@Getter
@AllArgsConstructor
public class PreviewResponse {
    @Schema(description = "整体结论：TRIGGER | NO_TRIGGER | INSUFFICIENT_DATA")
    private final String result;

    @Schema(description = "逐点判定")
    private final List<PreviewPoint> points;
}
