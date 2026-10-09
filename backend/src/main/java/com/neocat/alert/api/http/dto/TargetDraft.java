package com.neocat.alert.api.http.dto;

import lombok.AllArgsConstructor;
import lombok.Getter;
import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;

@Schema(name = "AlertTargetDraft", description = "告警目标草稿；卡片结果目标用 cardId，原始统计项目标用服务与维度")
@Getter
@AllArgsConstructor
public class TargetDraft {
    @Schema(description = "目标类型：CARD | STAT 等，按后端可用目标返回")
    private final String kind;

    @Schema(description = "卡片 ID；kind=CARD 时使用")
    private final long cardId;

    @Schema(description = "服务名；原始统计项目标使用")
    private final String service;

    @Schema(description = "报表类型；原始统计项目标使用")
    private final String reportKind;

    @Schema(description = "维度 Type")
    private final String type;

    @Schema(description = "维度 Name")
    private final String name;

    @Schema(description = "卡片公式引用的统计项，仅用于目标描述", nullable = true)
    private final List<String> formulaStats;
}
