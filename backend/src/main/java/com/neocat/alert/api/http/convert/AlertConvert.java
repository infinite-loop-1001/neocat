package com.neocat.alert.api.http.convert;

import com.google.common.collect.Lists;
import com.neocat.alert.api.http.dto.AlertDtos.*;
import com.neocat.alert.domain.rule.*;
import com.neocat.alert.domain.engine.PreviewResult;
import com.neocat.query.domain.stat.Stat;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import java.util.List;
import java.util.Objects;
import org.apache.commons.collections4.CollectionUtils;
import org.apache.commons.collections4.ListUtils;

/**
 * 告警 HTTP 契约与领域模型之间的转换（PRD 06 §1、§2、§4）。
 *
 * <p>MapStruct 在编译期生成实现并注册为 Spring Bean（{@code componentModel = "spring"}）：
 * 接口只声明映射形状，字段对应关系由 {@code @Mapping} 表达，不再手写逐字段的静态转换函数。
 *
 * <p>规则的创建仍调用 {@link AlertRule#draft} 而不是直接构造：id、启用状态、窗口基线与三个集合的
 * 防御性拷贝属于聚合的初始状态不变量，不能复制到转换层。
 */
@Mapper(componentModel = "spring")
public interface AlertConvert {

    /** 新建规则草稿；未给出的字段按原入口行为取默认值。 */
    default AlertRule rule(AlertDraft draft, AlertScope scope) {
        return AlertRule.draft(scope, draft.getOrgId(),
                Objects.isNull(draft.getName()) ? "未命名规则" : draft.getName(),
                Objects.isNull(draft.getDescription()) ? "" : draft.getDescription(),
                Objects.isNull(draft.getTarget()) ? AlertTarget.rawMetric("", "TRANSACTION", null, null) : target(draft.getTarget()),
                Objects.isNull(draft.getCombinator()) ? Combinator.AND : Combinator.valueOf(draft.getCombinator()),
                draft.getWindowPoints(),
                CollectionUtils.isEmpty(draft.getConditions()) ? Lists.newArrayList() : conditions(draft.getConditions()),
                ListUtils.emptyIfNull(draft.getRecipients()),
                CollectionUtils.isEmpty(draft.getChannels()) ? Lists.newArrayList() : channels(draft.getChannels()));
    }

    /** 条件列表：统计项与比较符在边界处解析为枚举。 */
    List<Condition> conditions(List<ConditionDraft> drafts);

    /** 通道列表：字符串按枚举名解析。 */
    List<AlertChannel> channels(List<String> names);

    /**
     * 统计项：沿用 {@link Stat#parse} 的裁剪与大写归一，因此不能交给 String → 枚举的默认转换。
     */
    default Stat stat(String raw) {
        return Stat.parse(raw);
    }

    /** 目标：卡片结果目标携带卡片 ID 与公式统计项，其余按原始指标目标构造。 */
    // question: 为什么这里要直接引用 query.domain 模块的值对象?
    // rules: 这里用枚举 code 判断呢, 不要用常量值判断
    default AlertTarget target(TargetDraft draft) {
        List<Stat> stats = ListUtils.emptyIfNull(draft.getFormulaStats()).stream().map(Stat::parse).toList();
        return "CARD_RESULT".equalsIgnoreCase(draft.getKind())
                ? AlertTarget.cardResult(draft.getCardId(), draft.getService(), draft.getReportKind(), draft.getType(), draft.getName(), stats)
                : AlertTarget.rawMetric(draft.getService(), draft.getReportKind(), draft.getType(), draft.getName());
    }

    /** 规则响应：领域枚举按名称出参，条件与目标递归转换为对应 DTO。 */
    RuleResponse response(AlertRule rule);

    /** 目标响应：缺失的分类与名称按原契约保留为空串。 */
    @Mapping(target = "type", source = "type", defaultValue = "")
    @Mapping(target = "name", source = "name", defaultValue = "")
    TargetResponse target(AlertTarget target);

    /** 试算响应：逐点判定明细保持原结构。 */
    PreviewResponse preview(PreviewResult result);
}
