package com.neocat.dashboard.infra.jdbc

import com.neocat.dashboard.domain.dashboard.Dashboard
import com.neocat.organization.api.internal.OrgResourceIndex
import spock.lang.Specification

class DashboardRepositoryProjectionSpec extends Specification {
    def 'a newly inserted dashboard synchronously updates the organization projection'() {
        given:
        def mapper = Mock(DashboardMapper)
        def index = Mock(OrgResourceIndex)
        def repository = new DashboardRepositoryAdapter(mapper, index)

        when:
        def result = repository.save(new Dashboard(0L, 7L, '订单', 0))

        then:
        1 * mapper.insertDashboard({ row -> row.orgId == 7L && row.name == '订单' }) >>
                { args -> args[0].id = 12L; 1 }
        1 * index.dashboard(7L, 12L, '订单')
        result.getId() == 12L
    }
}
