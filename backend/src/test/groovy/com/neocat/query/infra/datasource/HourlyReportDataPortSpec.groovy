package com.neocat.query.infra.datasource

import com.neocat.analysis.domain.bucket.SeriesKey
import com.neocat.analysis.domain.bucket.SeriesKind
import com.neocat.analysis.infra.store.InMemoryHourlyReportStore
import com.neocat.common.time.bucket.DefaultTimeBucketResolver
import com.neocat.common.time.bucket.Granularity
import spock.lang.Specification

import java.time.Instant
import java.time.ZoneOffset

class HourlyReportDataPortSpec extends Specification {
    static final Instant FROM = Instant.parse('2026-09-24T04:00:00Z')

    def 'current hour metric with value only keeps its normalized labels and is visible to the catalog'() {
        given:
        def store = new InMemoryHourlyReportStore()
        def key = SeriesKey.metric('order', 'order.amount', 'city=上海;')
        store.addValue(key, FROM.plusSeconds(12), 128.5d)
        def port = new HourlyReportDataPort(store, new DefaultTimeBucketResolver(), { ZoneOffset.UTC })

        when:
        def rows = port.rows('METRIC', 'order', 'order.amount', 'city=上海;',
                FROM, FROM.plusSeconds(60), Granularity.MINUTE_1, [])

        then:
        rows.size() == 1
        rows[0].valueCount() == 1L
        rows[0].key().getMetricLabels() == 'city=上海;'
        port.typesOf('METRIC', 'order', FROM, FROM.plusSeconds(60)) == ['order.amount']
        port.namesOf('METRIC', 'order', 'order.amount', FROM, FROM.plusSeconds(60)) == ['city=上海;']
    }

    def 'problem series retain their classification key when read from the current hour'() {
        given:
        def store = new InMemoryHourlyReportStore()
        def key = SeriesKey.problem('order', 'SLOW_SQL', 'select_order')
        store.add(key, FROM.plusSeconds(12), 150, false)
        def port = new HourlyReportDataPort(store, new DefaultTimeBucketResolver(), { ZoneOffset.UTC })

        expect:
        port.rows('PROBLEM', 'order', 'SLOW_SQL', 'select_order', FROM,
                FROM.plusSeconds(60), Granularity.MINUTE_1, [])[0].key() == key
    }
}
