package com.neocat.alert.api.http.convert;

import com.neocat.alert.api.http.dto.AlertDtos.*;
import com.neocat.alert.domain.rule.*;
import com.neocat.alert.domain.engine.PreviewResult;
import com.neocat.query.domain.stat.Stat;

import java.util.Collections;
import java.util.List;
import java.util.Objects;

// fixme: 使用 mapstruct 而不是静态函数
public final class AlertConvert {
    private AlertConvert() {
    }

    public static AlertRule rule(AlertDraft draft, AlertScope scope) {
        // rules: 判空使用 Objects.isNull() 或者 Objects.nonNull()
        List<Condition> conditions = draft.getConditions() == null ? List.of() : draft.getConditions().stream()
                .map(c -> new Condition(Stat.parse(c.getStat()), Comparator.valueOf(c.getComparator()), c.getThreshold())).toList();
        return AlertRule.draft(scope, draft.getOrgId(), draft.getName() == null ? "未命名规则" : draft.getName(),
                draft.getDescription() == null ? "" : draft.getDescription(),
                draft.getTarget() == null ? AlertTarget.rawMetric("", "TRANSACTION", null, null) : target(draft.getTarget()),
                draft.getCombinator() == null ? Combinator.AND : Combinator.valueOf(draft.getCombinator()),
                // rules: 禁止使用 List.of(), 因为他返回的是一个不可变 List, 使用 Apache common 包的 CollectionUtils 创建空
                //  容器, 包括单不限于 List, Set, Map 等等
                draft.getWindowPoints(), conditions, draft.getRecipients() == null ? List.of() : draft.getRecipients(),
                draft.getChannels() == null ? List.of() : draft.getChannels().stream().map(AlertChannel::valueOf).toList());
    }

    private static AlertTarget target(TargetDraft draft) {
        // question: 为什么这里要直接引用 query.domain 模块的值对象?
        List<Stat> stats = draft.getFormulaStats() == null ? List.of() : draft.getFormulaStats().stream().map(Stat::parse).toList();
        // rules: 这里用枚举 code 判断呢, 不要用常量值判断
        return "CARD_RESULT".equalsIgnoreCase(draft.getKind())
                ? AlertTarget.cardResult(draft.getCardId(), draft.getService(), draft.getReportKind(), draft.getType(), draft.getName(), stats)
                : AlertTarget.rawMetric(draft.getService(), draft.getReportKind(), draft.getType(), draft.getName());
    }

    public static RuleResponse response(AlertRule rule) {
        AlertTarget target = rule.getTarget();
        return new RuleResponse(rule.getId(), rule.getScope().name(), rule.getOrgId(), rule.getName(),
                rule.getCombinator().name(), rule.getWindowPoints(), rule.isEnabled(), rule.isInvalid(), rule.getRecipients(),
                rule.getChannels().stream().map(Enum::name).toList(), rule.getConditions().stream()
                        .map(c -> new ConditionDraft(c.getStat().name(), c.getComparator().name(), c.getThreshold())).toList(),
                new TargetResponse(target.getKind().name(), target.getCardId(), target.getService(), target.getReportKind(),
                        target.getType() == null ? "" : target.getType(), target.getName() == null ? "" : target.getName()));
    }

    public static PreviewResponse preview(PreviewResult result) {
        return new PreviewResponse(result.getResult().name(), result.getPoints().stream()
                .map(p -> new PreviewPoint(p.getMinute(), p.isKnown(), p.isSatisfied(), p.getMissingStat()))
                .toList());
    }
}
