package com.neocat.dashboard.infra.service

import com.neocat.dashboard.domain.card.Card
import com.neocat.dashboard.domain.dashboard.Dashboard
import com.neocat.dashboard.domain.dashboard.DashboardRepository
import spock.lang.Specification

class StoredCardReferencesSpec extends Specification {
    def 'only a surviving same-leaf same-target formula can retain a raw alert'() {
        given:
        def repository = Stub(DashboardRepository) {
            byOrg(7L) >> [new Dashboard(1L, 7L, 'first', 0)]
            cardsOf(1L) >> [Card.withoutThresholds(12L, 1L, 'order', 'TRANSACTION', 'URL', '/a',
                    null, [], 'hits * 2', 'RECENT_1H', 0),
                    Card.withoutThresholds(13L, 1L, 'order', 'TRANSACTION', 'URL', '/b',
                            null, [], 'failures', 'RECENT_1H', 1)]
        }
        def references = new StoredCardReferences(repository)

        expect:
        references.referenced(7L, 'order', 'TRANSACTION', 'URL', '/a', null, 'HITS')
        !references.referenced(7L, 'order', 'TRANSACTION', 'URL', '/a', null, 'FAILURES')
        !references.referenced(7L, 'order', 'TRANSACTION', 'URL', '/c', null, 'HITS')
    }
}
