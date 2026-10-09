package com.neocat.dashboard.api.http.dto;

import lombok.AllArgsConstructor;
import lombok.Getter;
import io.swagger.v3.oas.annotations.media.Schema;

@Schema(name = "DashboardResponse", description = "大盘")
@Getter
@AllArgsConstructor
public class DashboardResponse {
    @Schema(description = "大盘 ID")
    private final long id;

    @Schema(description = "所属叶子组织 ID")
    private final long orgId;

    @Schema(description = "大盘名称")
    private final String name;
}
