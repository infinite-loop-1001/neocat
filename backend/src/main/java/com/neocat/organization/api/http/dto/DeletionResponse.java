package com.neocat.organization.api.http.dto;

import lombok.AllArgsConstructor;
import lombok.Getter;
import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;

@Schema(name = "OrgDeletionPreview", description = "删除组织节点的影响面")
@Getter
@AllArgsConstructor
public class DeletionResponse {
    @Schema(description = "节点名称")
    private final String orgName;

    @Schema(description = "将被级联删除的大盘")
    private final List<DashboardSummary> dashboards;

    @Schema(description = "将被级联删除的组织告警规则数")
    private final long alertRuleCount;

    @Schema(description = "受影响的成员数")
    private final long memberCount;
}
