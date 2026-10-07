package com.neocat.alert.infra.listener;

import com.neocat.alert.domain.rule.AlertRule;
import com.neocat.alert.domain.rule.AlertRuleRepository;
import com.neocat.alert.domain.rule.AlertTarget;
import com.neocat.alert.domain.rule.AlertTargetKind;
import com.neocat.dashboard.api.internal.CardChange;
import com.neocat.dashboard.api.internal.CardReferences;
import com.neocat.query.domain.stat.Stat;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Objects;

/** Reconciles isOrganization alerts in the same MySQL transaction as a card update. */
@Component
public class CardChangeListener {
    private final AlertRuleRepository rules;

    private final CardReferences references;

    public CardChangeListener(AlertRuleRepository rules, CardReferences references) {
        this.rules = rules;
        this.references = references;
    }
    @EventListener
    public void on(CardChange event) {
        for (AlertRule rule : rules.byOrg(event.getOrgId())) {
            AlertTarget target = rule.getTarget();
            if (target == null) {
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
                    rules.clearWindowState(rule.getId());
                    rules.save(new AlertRule(rule.getId(), rule.getScope(), rule.getOrgId(), rule.getName(),
                            rule.getDescription(), updated, rule.getCombinator(), rule.getWindowPoints(),
                            rule.getConditions(), rule.getRecipients(), rule.getChannels(), false, rule.isInvalid(), null));
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
        rules.clearWindowState(rule.getId());
        rules.save(rule.withInvalid(true).withEnabled(false, null));
    }
    private boolean sameTarget(AlertTarget target, CardChange event) {
        return Objects.equals(target.getService(), event.getService())
                && Objects.equals(target.getReportKind(), event.getReportKind())
                && Objects.equals(target.getType(), event.getType())
                && Objects.equals(target.getName(), event.getName())
                && Objects.equals(target.getMetricLabels(), event.getMetricLabels());
    }
}
