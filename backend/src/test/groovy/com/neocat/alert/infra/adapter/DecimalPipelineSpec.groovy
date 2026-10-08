package com.neocat.alert.infra.adapter

import com.neocat.alert.domain.engine.PreviewService
import com.neocat.alert.domain.engine.PreviewResultType
import com.neocat.alert.domain.rule.AlertChannel
import com.neocat.alert.domain.rule.AlertRule
import com.neocat.alert.domain.rule.AlertScope
import com.neocat.alert.domain.rule.AlertTarget
import com.neocat.alert.domain.rule.Combinator
import com.neocat.alert.domain.rule.Comparator
import com.neocat.alert.domain.rule.Condition
import com.neocat.analysis.domain.bucket.AggregatedRow
import com.neocat.analysis.domain.bucket.AggregationLevel
import com.neocat.analysis.domain.bucket.SeriesKey
import com.neocat.analysis.domain.bucket.SeriesKind
import com.neocat.dashboard.domain.card.Card
import com.neocat.dashboard.domain.card.CardSeriesService
import com.neocat.dashboard.domain.dashboard.DashboardRepository
import com.neocat.dashboard.infra.service.CardResultsService
import com.neocat.dashboard.infra.service.ReportCardInputSource
import com.neocat.query.domain.stat.Stat
import com.neocat.query.domain.stat.StatCalculator
import com.neocat.query.infra.port.ReportDataPort
import com.neocat.query.infra.service.ReportPointsService
import java.time.Instant
import spock.lang.Specification

/** 真实十进制统计→卡片→告警适配与预览，只有原始报表端口离线 Stub。 */
class DecimalPipelineSpec extends Specification {
    def '卡片输入不提前六位舍入，告警比较与展示使用同一最终结果'() {
        given:
        def from = Instant.parse('2026-10-08T10:00:00Z')
        def row = new AggregatedRow(SeriesKey.of('order', SeriesKind.TRANSACTION, 'URL', '/a', 'all'),
                from, AggregationLevel.MINUTE, 60L)
        row.addCount(3L, 1L, 1L, 0L, 1L)
        def data = Stub(ReportDataPort) { rows(_, _, _, _, _, _, _, _) >> [row] }
        def reports = new ReportPointsService(data, new StatCalculator())
        def input = new ReportCardInputSource(reports)
        def series = new CardSeriesService(input)
        def card = Card.withoutThresholds(1L, 1L, 'order', 'TRANSACTION', 'URL', '/a', null,
                [], 'avgDuration * 10', 'RECENT_1H', 0)
        def repository = Stub(DashboardRepository) { findCard(1L) >> card }
        def cards = new CardResultsService(repository, series)
        def source = new ReportMinutePointSource(reports, cards)
        def target = AlertTarget.cardResult(1L, 'order', 'TRANSACTION', 'URL', '/a', [Stat.AVG])
        def rule = AlertRule.draft(AlertScope.SERVICE, null, 'precise', '', target, Combinator.AND, 1,
                [new Condition(Stat.AVG, Comparator.EQ, 3.333333)], [1L], [AlertChannel.EMAIL])

        expect:
        input.buckets(card, from, from.plusSeconds(60), 60L)[from.toEpochMilli()][Stat.AVG]
                .toPlainString() == '0.3333333'
        cards.value(1L, from, from.plusSeconds(60)).toPlainString() == '3.333333'
        source.values(target, from.toEpochMilli(), [Stat.AVG])[Stat.AVG].toPlainString() == '3.333333'
        new PreviewService(source).preview(rule, from.toEpochMilli()).getResult() == PreviewResultType.TRIGGER

        and: '直接统计告警的完整结果应先舍入六位'
        source.values(AlertTarget.rawMetric('order', 'TRANSACTION', 'URL', '/a'), from.toEpochMilli(),
                [Stat.AVG])[Stat.AVG].toPlainString() == '0.333333'
    }
}
