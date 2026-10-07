package com.neocat.query.infra.service

import com.neocat.query.infra.port.ReportDataPort

import com.neocat.analysis.domain.bucket.AggregatedRow
import com.neocat.common.time.bucket.Granularity
import com.neocat.query.domain.stat.Stat
import com.neocat.query.domain.stat.StatCalculator
import spock.lang.Specification

import java.time.Instant

class ReportPointsServiceSpec extends Specification {
    def 'missing rows stay null rather than becoming zero'() {
        given:
        def reports = Mock(ReportDataPort)
        def points = new ReportPointsService(reports, new StatCalculator())
        def from = Instant.parse('2026-09-24T04:00:00Z')

        when:
        def result = points.values('TRANSACTION', 'order', 'URL', '/a', from,
                from.plusSeconds(60), ['HITS', 'QPS'], [])

        then:
        1 * reports.rows('TRANSACTION', 'order', 'URL', '/a', from, from.plusSeconds(60), Granularity.MINUTE_1, []) >> []
        result.containsKey('HITS') && result.HITS == null
        result.containsKey('QPS') && result.QPS == null
    }
}
