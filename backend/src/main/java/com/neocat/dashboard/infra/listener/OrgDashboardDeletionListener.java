package com.neocat.dashboard.infra.listener;

import com.neocat.dashboard.domain.dashboard.DashboardRepository;
import com.neocat.organization.domain.lifecycle.OrgDeletionRequested;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

@Component
public class OrgDashboardDeletionListener {
    private final DashboardRepository dashboards;

    public OrgDashboardDeletionListener(DashboardRepository dashboards) {
        this.dashboards = dashboards;
    }
    @EventListener
    public void on(OrgDeletionRequested event) {
        dashboards.byOrg(event.getOrgId()).forEach(d -> dashboards.delete(d.getId()));
    }
}
