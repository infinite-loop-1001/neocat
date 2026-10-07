package com.neocat.dashboard.api.internal;

import java.util.List;

/** Stable cross-module card change, published synchronously within the card transaction. */
@lombok.Getter
@lombok.EqualsAndHashCode
@lombok.ToString
public class CardChange {
    private final long cardId;

    private final long orgId;

    private final String service;

    private final String reportKind;

    private final String type;

    private final String name;

    private final String metricLabels;

    private final String formula;

    private final List<String> formulaStats;

    private final boolean deleted;

    public CardChange(long cardId, long orgId, String service, String reportKind, String type, String name, String metricLabels, String formula, List<String> formulaStats, boolean deleted) {
        this.cardId = cardId;
        this.orgId = orgId;
        this.service = service;
        this.reportKind = reportKind;
        this.type = type;
        this.name = name;
        this.metricLabels = metricLabels;
        this.formula = formula;
        this.formulaStats = formulaStats;
        this.deleted = deleted;
    }

}







