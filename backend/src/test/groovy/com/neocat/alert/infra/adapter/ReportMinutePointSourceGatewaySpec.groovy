package com.neocat.alert.infra.adapter


import com.neocat.alert.domain.entity.vo.rule.AlertTarget
import com.neocat.dashboard.api.internal.CardResults
import com.neocat.query.api.internal.ReportPoints
import com.neocat.query.domain.stat.Stat
import spock.lang.Specification

import java.time.Instant

class ReportMinutePointSourceGatewaySpec extends Specification {
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
                ['HITS', 'AVG'], []) >> [HITS: 0.0, AVG: null]
        values.getValues().containsKey(Stat.HITS) && values.getValues()[Stat.HITS] == 0.0
        values.getValues().containsKey(Stat.AVG) && values.getValues()[Stat.AVG] == null
    }

    def 'batch reads every complete minute independently'() {
        given:
        def reports = Mock(ReportPoints)
        def source = new ReportMinutePointSource(reports)
        def first = Instant.parse('2026-09-24T04:00:00Z')
        def second = first.plusSeconds(60)
        def target = AlertTarget.rawMetric('order', 'TRANSACTION', 'URL', '/a')

        when:
        def points = source.batchMinuteValues(target, [first.toEpochMilli(), second.toEpochMilli()], [Stat.HITS])

        then:
        1 * reports.values('TRANSACTION', 'order', 'URL', '/a', first, second, ['HITS'], []) >> [HITS: 0.0]
        1 * reports.values('TRANSACTION', 'order', 'URL', '/a', second, second.plusSeconds(60), ['HITS'], []) >> [HITS: null]
        points*.minute == [first.toEpochMilli(), second.toEpochMilli()]
        points[0].values[Stat.HITS] == 0.0
        points[1].values[Stat.HITS] == null
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
        1 * cards.value(12L, from, from.plusSeconds(60)) >> 7.0
        0 * reports._
        values.getValues()[Stat.HITS] == 7.0
    }
}
