package com.neocat.catalog.domain.service

import com.neocat.catalog.domain.entry.InstanceEntry
import com.neocat.catalog.domain.entry.ServiceEntry
import com.neocat.catalog.domain.report.ReportKind
import com.neocat.catalog.domain.report.SeriesPresence
import com.neocat.catalog.domain.report.TimeRange

import spock.lang.Specification

import java.time.Instant

class CatalogFilterSpec extends Specification {
    static final Instant NOW = Instant.parse('2026-09-24T04:00:00Z')
    static final TimeRange RANGE = new TimeRange(NOW.minusSeconds(3600), NOW)

    CatalogRepository repository = Mock()
    SeriesPresence presence = Mock()
    CatalogService service = new CatalogService(repository, presence)

    def 'only services with data for the requested kind and range are shown, sorted'() {
        when:
        def result = service.servicesWithData(ReportKind.TRANSACTION, RANGE)

        then:
        1 * repository.services() >> [entry('pay'), entry('cart'), entry('order')]
        1 * presence.hasData('pay', ReportKind.TRANSACTION, RANGE) >> true
        1 * presence.hasData('cart', ReportKind.TRANSACTION, RANGE) >> false
        1 * presence.hasData('order', ReportKind.TRANSACTION, RANGE) >> true
        result == ['order', 'pay']
    }

    def 'historical range is forwarded unchanged to the report adapter'() {
        given:
        def historical = new TimeRange(NOW.minusSeconds(86400), NOW.minusSeconds(82800))

        when:
        def result = service.servicesWithData(ReportKind.EVENT, historical)

        then:
        1 * repository.services() >> [entry('cart')]
        1 * presence.hasData('cart', ReportKind.EVENT, historical) >> true
        result == ['cart']
    }

    def 'instances are filtered by the same kind and range independently'() {
        when:
        def result = service.instancesWithData('order', ReportKind.TRANSACTION, RANGE)

        then:
        1 * repository.instancesOf('order') >> [instance('order', '10.0.0.9'), instance('order', '10.0.0.8')]
        1 * presence.hasInstanceData('order', '10.0.0.9', ReportKind.TRANSACTION, RANGE) >> false
        1 * presence.hasInstanceData('order', '10.0.0.8', ReportKind.TRANSACTION, RANGE) >> true
        result == ['10.0.0.8']
    }

    def 'no catalog entries returns empty without consulting the report adapter'() {
        when:
        def result = service.servicesWithData(ReportKind.TRANSACTION, RANGE)

        then:
        1 * repository.services() >> []
        0 * presence._
        result == []
    }

    private static ServiceEntry entry(String name) { new ServiceEntry(name, NOW, NOW) }
    private static InstanceEntry instance(String service, String id) { new InstanceEntry(service, id, NOW, NOW) }
}
