package com.neocat.dashboard.api.http.dto.dashboard;

import lombok.AllArgsConstructor;
import lombok.Getter;
import io.swagger.v3.oas.annotations.media.Schema;

@Schema(name = "DashboardDraft", description = "大盘草稿")
@Getter
@AllArgsConstructor
public class DashboardDraft {
    @Schema(description = "所属叶子组织 ID", requiredMode = Schema.RequiredMode.REQUIRED)
    private final long orgId;

    @Schema(description = "大盘名称", requiredMode = Schema.RequiredMode.REQUIRED)
    private final String name;
}
