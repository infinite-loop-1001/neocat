package com.neocat.query.infra.service

import com.neocat.analysis.domain.bucket.AggregatedRow
import com.neocat.analysis.domain.bucket.AggregationLevel
import com.neocat.analysis.domain.bucket.SeriesKey
import com.neocat.analysis.domain.bucket.SeriesKind
import com.neocat.common.time.bucket.DefaultTimeBucketResolver
import com.neocat.common.time.clock.TimeProvider
import com.neocat.query.domain.report.ReportTableService
import com.neocat.query.domain.series.MomAligner
import com.neocat.query.domain.series.QualityResolver
import com.neocat.query.domain.stat.StatCalculator
import com.neocat.query.infra.port.ReportDataPort
import com.neocat.query.infra.port.SamplePort
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import spock.lang.Specification

class DecimalReportSeriesSpec extends Specification {
    def cleanup() {
        TimeProvider.clock = Clock.systemUTC()
    }

    def '趋势与环比先合并原始行，再算统计值且缺数保持 null'() {
        given:
        def time = Instant.parse('2026-10-08T10:00:00Z')
        TimeProvider.clock = Clock.fixed(time.plusSeconds(60), ZoneOffset.UTC)
        def data = Stub(ReportDataPort) {
            rows(_, _, _, _, _, _, _, _) >> { args ->
                def start = args[4]
                def one = row(start, 'one')
                one.addCount(1L, 1L, 1L, 1L, 1L)
                def two = row(start, 'two')
                two.addCount(2L, 0L, 20L, 10L, 10L)
                [one, two]
            }
        }
        def service = new ReportQueryService(data, new DefaultTimeBucketResolver(), new ReportTableService(),
                new StatCalculator(), new QualityResolver(), new MomAligner(), Stub(SamplePort), { ZoneOffset.UTC })

        when:
        def result = service.series('order', 'TRANSACTION', 'URL', '/a', stat,
                'HOUR:' + time.toEpochMilli(), 60, 'DAY', 'one,two')

        then:
        result.points[0].value.toPlainString() == expected
        result.mom.points[0].value.toPlainString() == expected
        result.points[1].value == null
        result.mom.points[1].value == null

        where:
        stat           | expected
        'AVG'          | '7.000000'
        'FAILURE_RATE' | '0.333333'
        'QPS'          | '0.050000'
    }

    def '确认零调用的耗时趋势不崩溃也不补零'() {
        given:
        def time = Instant.parse('2026-10-08T10:00:00Z')
        TimeProvider.clock = Clock.fixed(time.plusSeconds(60), ZoneOffset.UTC)
        def data = Stub(ReportDataPort) { rows(_, _, _, _, _, _, _, _) >> [row(time, 'all')] }
        def service = new ReportQueryService(data, new DefaultTimeBucketResolver(), new ReportTableService(),
                new StatCalculator(), new QualityResolver(), new MomAligner(), Stub(SamplePort), { ZoneOffset.UTC })
        expect:
        service.series('order', 'TRANSACTION', 'URL', '/a', 'AVG', 'HOUR:' + time.toEpochMilli(),
                60, null, null).points[0].value == null
    }

    private static AggregatedRow row(Instant time, String instance) {
        new AggregatedRow(SeriesKey.of('order', SeriesKind.TRANSACTION, 'URL', '/a', instance),
                time, AggregationLevel.MINUTE, 60L)
    }
}
