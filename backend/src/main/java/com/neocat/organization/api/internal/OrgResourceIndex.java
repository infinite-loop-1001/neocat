package com.neocat.organization.api.internal;

/** Synchronous MySQL projection of resources owned by a leaf isOrganization. */
public interface OrgResourceIndex {
    void dashboard(long orgId, long dashboardId, String name);
    void removeDashboard(long orgId, long dashboardId);
    void cardCount(long orgId, long dashboardId, long count);
    void alertRule(long orgId, long ruleId);
    void removeAlertRule(long orgId, long ruleId);
}
