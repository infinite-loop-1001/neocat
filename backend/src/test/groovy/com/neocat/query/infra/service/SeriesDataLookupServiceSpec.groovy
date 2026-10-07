package com.neocat.query.infra.service

import com.neocat.query.infra.port.ReportDataPort

import spock.lang.Specification

import java.time.Instant

class SeriesDataLookupServiceSpec extends Specification {
    static final Instant FROM = Instant.parse('2026-09-24T03:00:00Z')
    static final Instant TO = Instant.parse('2026-09-24T04:00:00Z')

    ReportDataPort reports = Mock()
    SeriesDataLookupService lookup = new SeriesDataLookupService(reports)

    def 'service presence uses the type directory, not an impossible null type/name series'() {
        when:
        def exists = lookup.hasData('order', 'TRANSACTION', null, FROM, TO)

        then:
        1 * reports.typesOf('TRANSACTION', 'order', FROM, TO) >> ['URL']
        0 * reports.rows(_, _, _, _, _, _, _, _)
        exists
    }

    def 'instance presence is restricted to the requested instance'() {
        when:
        def exists = lookup.hasData('order', 'TRANSACTION', 'order-2', FROM, TO)

        then:
        1 * reports.instancesWithData('TRANSACTION', 'order', FROM, TO) >> ['order-1']
        !exists
    }
}
