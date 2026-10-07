package com.neocat.dashboard.api.internal;

/** Query whether a deleted card's raw statistic is still referenced by another card in this leaf. */
public interface CardReferences {
    boolean referenced(long orgId, String service, String reportKind, String type,
                       String name, String metricLabels, String stat);
}
