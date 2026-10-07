package com.neocat.alert.infra.adapter

import com.neocat.alert.domain.rule.AlertTarget
import com.neocat.dashboard.api.internal.CardResults
import com.neocat.query.api.internal.ReportPoints
import com.neocat.query.domain.stat.Stat
import spock.lang.Specification

import java.time.Instant

class ReportMinutePointSourceSpec extends Specification {
    def 'zero is retained as data while missing statistics stay null'() {
        given:
        def reports = Mock(ReportPoints)
        def source = new ReportMinutePointSource(reports)
        def from = Instant.parse('2026-09-24T04:00:00Z')
        def target = AlertTarget.rawMetric('order', 'TRANSACTION', 'URL', '/a')

        when:
        def values = source.values(target, from.toEpochMilli(), [Stat.HITS, Stat.AVG])

        then:
        1 * reports.values('TRANSACTION', 'order', 'URL', '/a', from, from.plusSeconds(60),
                ['HITS', 'AVG'], []) >> [HITS: 0.0d, AVG: null]
        values.containsKey(Stat.HITS) && values[Stat.HITS] == 0.0d
        values.containsKey(Stat.AVG) && values[Stat.AVG] == null
    }

    def 'card result target uses the saved card formula rather than its raw metric'() {
        given:
        def reports = Mock(ReportPoints)
        def cards = Mock(CardResults)
        def source = new ReportMinutePointSource(reports, cards)
        def from = Instant.parse('2026-09-24T04:00:00Z')
        def target = AlertTarget.cardResult(12L, 'order', 'TRANSACTION', 'URL', '/a', [Stat.HITS])

        when:
        def values = source.values(target, from.toEpochMilli(), [Stat.HITS])

        then:
        1 * cards.value(12L, from, from.plusSeconds(60)) >> 7.0d
        0 * reports._
        values[Stat.HITS] == 7.0d
    }
}
