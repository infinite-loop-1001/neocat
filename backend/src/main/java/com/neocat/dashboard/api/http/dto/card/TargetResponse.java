package com.neocat.dashboard.api.http.dto.card;

import lombok.AllArgsConstructor;
import lombok.Getter;
import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;

@Schema(name = "AlertableTargetResponse", description = "组织告警可选目标；kind 区分卡片结果与原始统计项")
@Getter
@AllArgsConstructor
public class TargetResponse {
    @Schema(description = "目标类型：CARD_RESULT | STAT")
    private final String kind;

    @Schema(description = "卡片 ID；STAT 目标为 0")
    private final long cardId;

    @Schema(description = "服务名")
    private final String service;

    @Schema(description = "目标类型，如 TRANSACTION")
    private final String targetKind;

    @Schema(description = "目标维度 Type")
    private final String targetType;

    @Schema(description = "目标维度 Name")
    private final String targetName;

    @Schema(description = "该目标可用的统计项")
    private final List<String> stats;
}
