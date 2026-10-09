package com.neocat.alert.api.http.dto;

import lombok.AllArgsConstructor;
import lombok.Getter;
import io.swagger.v3.oas.annotations.media.Schema;

@Schema(name = "AlertTargetResponse", description = "可选告警目标")
@Getter
@AllArgsConstructor
public class TargetResponse {
    @Schema(description = "目标类型")
    private final String kind;

    @Schema(description = "卡片 ID")
    private final long cardId;

    @Schema(description = "服务名")
    private final String service;

    @Schema(description = "报表类型")
    private final String reportKind;

    @Schema(description = "维度 Type")
    private final String type;

    @Schema(description = "维度 Name")
    private final String name;
}
