package com.neocat.dashboard.infra.service

import com.neocat.dashboard.domain.card.Card
import com.neocat.dashboard.domain.card.CardPoint
import com.neocat.dashboard.domain.card.CardPointOutcome
import com.neocat.dashboard.domain.card.CardSeriesService
import com.neocat.dashboard.domain.dashboard.DashboardRepository
import spock.lang.Specification

import java.time.Instant

class CardResultsServiceSpec extends Specification {
    def 'reads the saved card and uses its evaluated minute, retaining missing data'() {
        given:
        def dashboards = Mock(DashboardRepository)
        def series = Mock(CardSeriesService)
        def card = Card.withoutThresholds(12L, 3L, 'order', 'TRANSACTION', 'URL', '/a',
                null, [], 'HITS', '1h', 0)
        def from = Instant.parse('2026-09-24T04:00:00Z')

        when:
        def result = new CardResultsService(dashboards, series).value(12L, from, from.plusSeconds(60))

        then:
        1 * dashboards.findCard(12L) >> card
        1 * series.series(card, from, from.plusSeconds(60), 60) >>
                [new CardPoint(from.toEpochMilli(), from.plusSeconds(60).toEpochMilli(),
                        null, CardPointOutcome.GAP, ['HITS'])]
        result == null
    }
}
