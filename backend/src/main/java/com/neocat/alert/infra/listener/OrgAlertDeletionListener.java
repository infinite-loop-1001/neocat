package com.neocat.alert.infra.listener;

import com.neocat.alert.domain.rule.AlertRuleRepository;
import com.neocat.organization.domain.lifecycle.OrgDeletionRequested;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

@Component
public class OrgAlertDeletionListener {
    private final AlertRuleRepository rules;

    public OrgAlertDeletionListener(AlertRuleRepository rules) {
        this.rules = rules;
    }
    @EventListener
    public void on(OrgDeletionRequested event) {
        rules.byOrg(event.getOrgId()).forEach(r -> rules.delete(r.getId()));
    }
}
