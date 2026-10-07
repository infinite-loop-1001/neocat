package com.neocat.dashboard.infra.listener

import com.neocat.dashboard.domain.dashboard.Dashboard
import com.neocat.dashboard.domain.dashboard.DashboardRepository
import com.neocat.organization.domain.lifecycle.OrgDeletionRequested
import spock.lang.Specification

class OrgDashboardDeletionListenerSpec extends Specification {
    def 'deletes only the requested organization dashboards synchronously'() {
        given:
        def dashboards = Mock(DashboardRepository)
        def listener = new OrgDashboardDeletionListener(dashboards)

        when:
        listener.on(new OrgDeletionRequested(7L))

        then:
        1 * dashboards.byOrg(7L) >> [new Dashboard(1L, 7L, 'a', 0),
                                     new Dashboard(2L, 7L, 'b', 1)]
        1 * dashboards.delete(1L)
        1 * dashboards.delete(2L)
        0 * dashboards._
    }
}
