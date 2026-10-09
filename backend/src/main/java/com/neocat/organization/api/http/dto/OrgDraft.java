package com.neocat.organization.api.http.dto;

import lombok.AllArgsConstructor;
import lombok.Getter;
import io.swagger.v3.oas.annotations.media.Schema;

@Schema(name = "OrgDraft", description = "组织节点草稿")
@Getter
@AllArgsConstructor
public class OrgDraft {
    @Schema(description = "节点名称；同层重名返回 409 NAME_DUPLICATED", requiredMode = Schema.RequiredMode.REQUIRED)
    private final String name;

    @Schema(description = "父节点 ID；省略或 null 表示根节点", nullable = true)
    private final Long parentId;
}
