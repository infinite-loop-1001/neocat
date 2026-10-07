package com.neocat.dashboard.infra.jdbc

import com.neocat.dashboard.domain.card.Card
import com.neocat.dashboard.domain.card.ThresholdDirection
import com.neocat.dashboard.domain.card.ThresholdLine
import com.neocat.organization.api.internal.OrgResourceIndex
import spock.lang.Specification

class DashboardRepositoryAdapterSpec extends Specification {
    def 'metric card persists labels, scope, derived formula unit and threshold lines'() {
        given:
        def mapper = Mock(DashboardMapper)
        def projection = Mock(OrgResourceIndex)
        def repository = new DashboardRepositoryAdapter(mapper, projection)
        def card = new Card(0L, 3L, 'order', 'METRIC', 'order.amount', '',
                'city=上海;', ['10.0.0.8'], 'hits / hits', 'RECENT_1H', 0,
                [new ThresholdLine(ThresholdDirection.ABOVE, 1.25d)])
        def dash = new DashboardRepositoryAdapter.DashboardRow()
        dash.id = 3L
        dash.orgId = 7L

        when:
        def saved = repository.saveCard(card)

        then:
        1 * mapper.insertCard({ row -> row.metricName == 'order.amount' &&
                row.metricLabels == '"city=上海;"' && row.instanceScope == '["10.0.0.8"]' &&
                row.formulaUnit == 'RATE' }) >> { args -> args[0].id = 12L; 1 }
        1 * mapper.deleteThresholdLines(12L)
        1 * mapper.insertThresholdLine(12L, 'ABOVE', 1.25d)
        1 * mapper.selectDashboard(3L) >> dash
        1 * mapper.countCards(3L) >> 1L
        1 * projection.cardCount(7L, 3L, 1L)
        saved.getMetricLabels() == 'city=上海;'
        saved.getInstanceScope() == ['10.0.0.8']
    }

    def 'reading a saved card restores selectors, instance scope and threshold lines'() {
        given:
        def mapper = Mock(DashboardMapper)
        def repository = new DashboardRepositoryAdapter(mapper)
        def row = new DashboardRepositoryAdapter.CardRow()
        row.id = 12L
        row.dashboardId = 3L
        row.service = 'order'
        row.targetKind = 'METRIC'
        row.targetType = 'order.amount'
        row.metricLabels = '"city=上海;"'
        row.instanceScope = '["10.0.0.8"]'
        row.formula = 'hits / hits'
        row.timeRange = 'RECENT_1H'

        when:
        def restored = repository.findCard(12L)

        then:
        1 * mapper.selectCard(12L) >> row
        1 * mapper.selectThresholdLines(12L) >> [new DashboardMapper.ThresholdLineRow('ABOVE', 1.25d)]
        restored.getMetricLabels() == 'city=上海;'
        restored.getInstanceScope() == ['10.0.0.8']
        restored.getThresholdLines() == [new ThresholdLine(ThresholdDirection.ABOVE, 1.25d)]
    }

    def 'deleting a dashboard explicitly cascades threshold lines and cards before the dashboard row'() {
        given:
        def mapper = Mock(DashboardMapper)
        def projection = Mock(OrgResourceIndex)
        def repository = new DashboardRepositoryAdapter(mapper, projection)
        def dash = new DashboardRepositoryAdapter.DashboardRow()
        dash.id = 3L
        dash.orgId = 7L
        def card = new DashboardRepositoryAdapter.CardRow()
        card.id = 12L

        when:
        repository.delete(3L)

        then:
        1 * mapper.selectDashboard(3L) >> dash
        1 * mapper.selectCardsByDashboard(3L) >> [card]
        1 * mapper.deleteThresholdLines(12L)
        1 * mapper.deleteCard(12L)
        1 * mapper.deleteDashboard(3L)
        1 * projection.removeDashboard(7L, 3L)
    }

    def 'deleting a card explicitly removes its threshold lines'() {
        given:
        def mapper = Mock(DashboardMapper)
        def repository = new DashboardRepositoryAdapter(mapper)
        def row = new DashboardRepositoryAdapter.CardRow()
        row.id = 12L
        row.dashboardId = 3L
        row.service = 'order'
        row.targetKind = 'METRIC'
        row.targetType = 'order.amount'
        row.formula = 'hits'
        row.timeRange = 'RECENT_1H'

        when:
        repository.deleteCard(12L)

        then:
        1 * mapper.selectCard(12L) >> row
        1 * mapper.selectThresholdLines(12L) >> []
        1 * mapper.deleteThresholdLines(12L)
        1 * mapper.deleteCard(12L)
    }
}
