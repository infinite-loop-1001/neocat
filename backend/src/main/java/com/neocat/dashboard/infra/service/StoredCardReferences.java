package com.neocat.dashboard.infra.service;

import com.neocat.dashboard.api.internal.CardReferences;
import com.neocat.dashboard.domain.dashboard.DashboardRepository;
import com.neocat.dashboard.domain.formula.FormulaParser;
import com.neocat.query.domain.stat.Stat;
import org.springframework.stereotype.Component;

import java.util.Objects;

@Component
public class StoredCardReferences implements CardReferences {
    private final DashboardRepository dashboards;

    private final FormulaParser parser;

    public StoredCardReferences(DashboardRepository dashboards) {
        this.dashboards = dashboards;
        this.parser = new FormulaParser();
    }
    @Override
    public boolean referenced(long orgId, String service, String reportKind, String type,
                              String name, String metricLabels, String stat) {
        Stat required = Stat.parse(stat);
        return dashboards.byOrg(orgId).stream().flatMap(d -> dashboards.cardsOf(d.getId()).stream())
                .anyMatch(card -> Objects.equals(card.getService(), service)
                        && Objects.equals(card.getTargetKind(), reportKind)
                        && Objects.equals(card.getTargetType(), type)
                        && Objects.equals(card.getTargetName(), name)
                        && Objects.equals(card.getMetricLabels(), metricLabels)
                        && parser.parse(card.getFormula()).valid()
                        && parser.parse(card.getFormula()).getFormula().referencedStats().contains(required));
    }
}
