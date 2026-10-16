package com.neocat.alert.application.handler;

import com.neocat.alert.domain.entity.AlertRule;
import com.neocat.alert.domain.entity.vo.rule.AlertTarget;
import com.neocat.alert.domain.entity.vo.rule.AlertTargetKind;
import com.neocat.alert.domain.repository.AlertRuleRepository;
import com.neocat.dashboard.api.internal.CardChange;
import com.neocat.dashboard.api.internal.CardReferences;
import com.neocat.query.domain.stat.Stat;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Objects;

/**
 * 卡片变更（PRD 06 §8，链路 31）。
 *
 * <p>与卡片更新同一个 MySQL 事务内同步维护该叶子的组织告警：
 * 卡片公式变更 → 规则跟随新公式并清零窗口；卡片删除 → 规则失效但保留配置。
 */
@Component
public class CardChangeEventHandler {

    private final AlertRuleRepository rules;

    private final CardReferences references;

    public CardChangeEventHandler(AlertRuleRepository rules, CardReferences references) {
        this.rules = rules;
        this.references = references;
    }
    @EventListener
    public void on(CardChange event) {
        for (AlertRule rule : rules.byOrg(event.getOrgId())) {
            AlertTarget target = rule.getTarget();
            if (Objects.isNull(target)) {
                continue;
            }
            if (target.isCardResult() && target.getCardId() == event.getCardId()) {
                if (event.isDeleted()) {
                    invalidate(rule);
                } else {
                    List<Stat> stats = event.getFormulaStats().stream().map(Stat::parse).toList();
                    AlertTarget updated = new AlertTarget(AlertTargetKind.CARD_RESULT, event.getCardId(),
                            event.getService(), event.getReportKind(), event.getType(), event.getName(),
                            event.getMetricLabels(), stats);
                    rules.clearAlertEvalCursor(rule.getId());
                    rules.save(new AlertRule(rule.getId(), rule.getScope(), rule.getOrgId(), rule.getName(),
                            rule.getDescription(), updated, rule.getCombinator(), rule.getWindowPoints(),
                            rule.getConditions(), rule.getRecipients(), rule.getChannels(), false, rule.isInvalid()));
                }
            } else if (!target.isCardResult() && sameTarget(target, event)
                    && rule.referencedStats().stream().anyMatch(stat ->
                    !references.referenced(event.getOrgId(), event.getService(), event.getReportKind(), event.getType(),
                            event.getName(), event.getMetricLabels(), stat.name()))) {
                invalidate(rule);
            }
        }
    }
    private void invalidate(AlertRule rule) {
        rules.clearAlertEvalCursor(rule.getId());
        rules.save(rule.withInvalid(true).withEnabled(false));
    }
    private boolean sameTarget(AlertTarget target, CardChange event) {
        return Objects.equals(target.getService(), event.getService())
                && Objects.equals(target.getReportKind(), event.getReportKind())
                && Objects.equals(target.getType(), event.getType())
                && Objects.equals(target.getName(), event.getName())
                && Objects.equals(target.getMetricLabels(), event.getMetricLabels());
    }
}
