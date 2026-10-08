package com.neocat.alert.api.http.dto;

import java.math.BigDecimal;

import lombok.AllArgsConstructor;
import lombok.Getter;
import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;

/**
 * 告警一期契约，无历史、严重度、确认字段。
 */
@Schema(name = "AlertDtos", description = "告警规则请求与响应契约容器")
// rules: 需要单独拆出文件, 不能都放到一个类里面
public final class AlertDtos {
    private AlertDtos() {
    }

    @Schema(name = "AlertDraft", description = "告警规则草稿；保存后始终为关闭状态")
    @Getter
    @AllArgsConstructor
    public static class AlertDraft {
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

    @Schema(name = "AlertTargetDraft", description = "告警目标草稿；卡片结果目标用 cardId，原始统计项目标用服务与维度")
    @Getter
    @AllArgsConstructor
    public static class TargetDraft {
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

    @Schema(name = "AlertConditionDraft", description = "单条比较条件")
    @Getter
    @AllArgsConstructor
    public static class ConditionDraft {
        @Schema(description = "统计项：HITS | FAILURES | FAILURE_RATE | QPS | AVG | MIN | MAX | TP50 等",
                requiredMode = Schema.RequiredMode.REQUIRED)
        private final String stat;

        @Schema(description = "比较符：GT | GTE | LT | LTE | EQ | NEQ", requiredMode = Schema.RequiredMode.REQUIRED)
        private final String comparator;

        @Schema(description = "阈值，必须显式非空：绝对值小于 10^14 且有效小数不超过 6 位，"
                        + "否则返回 400 INVALID_PARAM；比较按数值而非 scale",
                requiredMode = Schema.RequiredMode.REQUIRED, example = "0.05")
        private final BigDecimal threshold;
    }

    @Schema(name = "AlertTargetResponse", description = "可选告警目标")
    @Getter
    @AllArgsConstructor
    public static class TargetResponse {
        @Schema(description = "目标类型")
        private final String kind;

        @Schema(description = "卡片 ID")
        private final long cardId;

        @Schema(description = "服务名")
        private final String service;

        @Schema(description = "报表类型")
        private final String reportKind;

        @Schema(description = "维度 Type")
        private final String type;

        @Schema(description = "维度 Name")
        private final String name;
    }

    @Schema(name = "AlertRuleResponse", description = "告警规则；不含触发历史")
    @Getter
    @AllArgsConstructor
    public static class RuleResponse {
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

    @Schema(name = "AlertPreviewPoint", description = "预览的逐点判定；缺数点 known=false")
    @Getter
    @AllArgsConstructor
    public static class PreviewPoint {
        @Schema(description = "分钟起点（epoch millis，已回退评估延迟）")
        private final long minute;

        @Schema(description = "该点是否有足够数据参与判定")
        private final boolean known;

        @Schema(description = "该点是否满足全部条件；known=false 时无意义")
        private final boolean satisfied;

        @Schema(description = "缺数时指出缺失的统计项；不缺数为 null", nullable = true)
        private final String missingStat;
    }

    @Schema(name = "AlertPreviewResponse", description = "告警预览结果")
    @Getter
    @AllArgsConstructor
    public static class PreviewResponse {
        @Schema(description = "整体结论：TRIGGER | NO_TRIGGER | INSUFFICIENT_DATA")
        private final String result;

        @Schema(description = "逐点判定")
        private final List<PreviewPoint> points;
    }

    @Schema(name = "AlertChannelResponse", description = "通知通道及可用标记")
    @Getter
    @AllArgsConstructor
    public static class ChannelResponse {
        @Schema(description = "通道名：EMAIL | DINGTALK | FEISHU")
        private final String channel;

        @Schema(description = "当前是否可投递")
        private final boolean available;
    }

    @Schema(name = "AlertSuccess", description = "无数据体的成功响应")
    @Getter
    @AllArgsConstructor
    public static class Success {
        @Schema(description = "固定为 true")
        private final boolean ok;
    }
}
