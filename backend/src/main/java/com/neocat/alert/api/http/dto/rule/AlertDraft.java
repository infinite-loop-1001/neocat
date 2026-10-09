package com.neocat.alert.api.http.dto.rule;

import lombok.AllArgsConstructor;
import lombok.Getter;
import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;

@Schema(name = "AlertDraft", description = "告警规则草稿；保存后始终为关闭状态")
@Getter
@AllArgsConstructor
public class AlertDraft {
    @Schema(description = "规则范围：SERVICE | ORGANIZATION；省略按 SERVICE 处理",
            allowableValues = {"SERVICE", "ORGANIZATION"})
    private final String scope;

    @Schema(description = "组织 ID；scope=ORGANIZATION 时必填，否则返回 400 ALERT_ORG_REQUIRED", nullable = true)
    private final Long orgId;

    @Schema(description = "规则名称", requiredMode = Schema.RequiredMode.REQUIRED)
    private final String name;

    @Schema(description = "规则描述")
    private final String description;

    @Schema(description = "告警目标：组织告警必须是该叶子大盘已引用的统计项或卡片结果", nullable = true)
    private final TargetDraft target;

    @Schema(description = "多条件组合方式：AND | OR", allowableValues = {"AND", "OR"})
    private final String combinator;

    @Schema(description = "滑动窗口点数，所有条件共用；不支持逐条件配置")
    private final int windowPoints;

    @Schema(description = "比较条件，至少一条；嵌套或括号表达式不支持")
    private final List<ConditionDraft> conditions;

    @Schema(description = "收件人账号 ID 列表")
    private final List<Long> recipients;

    @Schema(description = "通知通道名列表，必须是平台已配置的通道")
    private final List<String> channels;
}
