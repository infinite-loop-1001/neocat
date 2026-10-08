package com.neocat.organization.api.http.dto;

import lombok.AllArgsConstructor;
import lombok.Getter;
import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;
import com.fasterxml.jackson.annotation.JsonCreator;

@Schema(name = "OrgDtos", description = "组织与成员请求、响应契约容器")
public final class OrgDtos {
    private OrgDtos() {
    }

    @Schema(name = "OrgDraft", description = "组织节点草稿")
    @Getter
    @AllArgsConstructor
    public static class OrgDraft {
        @Schema(description = "节点名称；同层重名返回 409 NAME_DUPLICATED", requiredMode = Schema.RequiredMode.REQUIRED)
        private final String name;

        @Schema(description = "父节点 ID；省略或 null 表示根节点", nullable = true)
        private final Long parentId;
    }

    @Schema(name = "MemberDraft", description = "组织成员草稿")
    @Getter
    @AllArgsConstructor(onConstructor_ = @JsonCreator(mode = JsonCreator.Mode.PROPERTIES))
    public static class MemberDraft {
        @Schema(description = "账号 ID", requiredMode = Schema.RequiredMode.REQUIRED)
        private final long userId;
    }

    @Schema(name = "OrgNode", description = "组织节点；leaf 与 memberCount 供管理界面展示")
    @Getter
    @AllArgsConstructor
    public static class OrgResponse {
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

    @Schema(name = "DashboardSummary", description = "删除预检中的大盘摘要")
    @Getter
    @AllArgsConstructor
    public static class DashboardSummary {
        @Schema(description = "大盘 ID")
        private final long id;

        @Schema(description = "大盘名称")
        private final String name;

        @Schema(description = "卡片数量")
        private final long cardCount;
    }

    @Schema(name = "OrgDeletionPreview", description = "删除组织节点的影响面")
    @Getter
    @AllArgsConstructor
    public static class DeletionResponse {
        @Schema(description = "节点名称")
        private final String orgName;

        @Schema(description = "将被级联删除的大盘")
        private final List<DashboardSummary> dashboards;

        @Schema(description = "将被级联删除的组织告警规则数")
        private final long alertRuleCount;

        @Schema(description = "受影响的成员数")
        private final long memberCount;
    }

    @Schema(name = "OrgSuccess", description = "无数据体的成功响应")
    @Getter
    @AllArgsConstructor
    public static class Success {
        @Schema(description = "固定为 true")
        private final boolean ok;
    }
}
