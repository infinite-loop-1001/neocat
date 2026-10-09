package com.neocat.alert.api.http.dto.rule;

import lombok.AllArgsConstructor;
import lombok.Getter;
import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;

@Schema(name = "AlertRuleResponse", description = "告警规则；不含触发历史")
@Getter
@AllArgsConstructor
public class RuleResponse {
    @Schema(description = "规则 ID")
    private final long id;

    @Schema(description = "规则范围：SERVICE | ORGANIZATION")
    private final String scope;

    @Schema(description = "组织 ID；服务告警为 null", nullable = true)
    private final Long orgId;

    @Schema(description = "规则名称")
    private final String name;

    @Schema(description = "多条件组合方式")
    private final String combinator;

    @Schema(description = "滑动窗口点数")
    private final int windowPoints;

    @Schema(description = "是否启用；保存后为 false，需手动启用")
    private final boolean enabled;

    @Schema(description = "目标是否已失效（卡片被删或目标变更）")
    private final boolean invalid;

    @Schema(description = "收件人账号 ID 列表")
    private final List<Long> recipients;

    @Schema(description = "通知通道名列表")
    private final List<String> channels;

    @Schema(description = "比较条件")
    private final List<ConditionDraft> conditions;

    @Schema(description = "告警目标")
    private final TargetResponse target;
}
