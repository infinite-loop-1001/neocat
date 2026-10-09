package com.neocat.organization.api.http.dto;

import lombok.AllArgsConstructor;
import lombok.Getter;
import io.swagger.v3.oas.annotations.media.Schema;

@Schema(name = "OrgNode", description = "组织节点；leaf 与 memberCount 供管理界面展示")
@Getter
@AllArgsConstructor
public class OrgResponse {
    @Schema(description = "节点 ID")
    private final long id;

    @Schema(description = "节点名称")
    private final String name;

    @Schema(description = "父节点 ID；根节点为 null", nullable = true)
    private final Long parentId;

    @Schema(description = "是否为叶子节点；只有叶子能承载大盘与组织告警")
    private final boolean leaf;

    @Schema(description = "有效成员数（含祖先继承）")
    private final int memberCount;
}
