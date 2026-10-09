package com.neocat.organization.api.http.dto;

import lombok.AllArgsConstructor;
import lombok.Getter;
import io.swagger.v3.oas.annotations.media.Schema;

@Schema(name = "DashboardSummary", description = "删除预检中的大盘摘要")
@Getter
@AllArgsConstructor
public class DashboardSummary {
    @Schema(description = "大盘 ID")
    private final long id;

    @Schema(description = "大盘名称")
    private final String name;

    @Schema(description = "卡片数量")
    private final long cardCount;
}
