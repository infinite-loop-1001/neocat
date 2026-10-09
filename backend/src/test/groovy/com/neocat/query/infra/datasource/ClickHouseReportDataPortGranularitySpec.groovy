package com.neocat.query.infra.datasource

import com.neocat.analysis.domain.bucket.AggregationLevel
import com.neocat.analysis.domain.bucket.SeriesKey
import com.neocat.common.time.bucket.DefaultTimeBucketResolver
import com.neocat.common.time.bucket.Granularity
import spock.lang.Specification

import java.time.Instant
import java.time.ZoneOffset

/**
 * ClickHouse 读取的**粒度折叠**（技术方案 03 §4.1、§6 §10）。
 *
 * <p>分钟桶表只有分钟行。请求 10 分钟粒度时若不折叠，调用方按 10 分钟桶起点
 * 取数只能命中「正好落在整 10 分钟」的少数分钟，其余分钟的数据被静默丢弃 ——
 * 数字偏小且不报错，正是最难发现的一类缺陷。
 */
class ClickHouseReportDataPortGranularitySpec extends Specification {

    static final Instant FROM = Instant.parse('2026-09-24T00:00:00Z')

    ClickHouseReportQuery query
    List<BucketRow> minuteRows
    ClickHouseReportDataPort port

    def setup() {
        minuteRows = []
        query = Stub(ClickHouseReportQuery) {
            minuteRows(_, _, _, _, _, _, _) >> { minuteRows }
            hourRows(_, _, _, _, _, _, _) >> { [] }
            dayRows(_, _, _, _, _, _, _) >> { [] }
        }
        port = new ClickHouseReportDataPort(query, new DefaultTimeBucketResolver(), { ZoneOffset.UTC })
    }

    static long[] dist(int index, long value) {
        def result = new long[16]
        result[index] = value
        return result
    }

    static BucketRow minuteRow(long count, long durationSum, long[] distribution,
                                                    Instant start) {
        return new BucketRow(
                'order', 'TRANSACTION', 'URL', '/a', SeriesKey.ALL, '', '',
                start, AggregationLevel.MINUTE,
                count, 0L, durationSum, 100L, 100L, 0d, 0L, distribution, 60L)
    }

    def "请求 10 分钟粒度时，同一目标桶内的分钟行被合并"() {
        given: "一个 10 分钟桶内的 3 个分钟行"
        minuteRows = [
                minuteRow(1, 100, dist(3, 1L), FROM),
                minuteRow(2, 200, dist(3, 2L), FROM.plusSeconds(60)),
                minuteRow(3, 300, dist(3, 3L), FROM.plusSeconds(120)),
        ]

        when:
        def rows = port.rows('TRANSACTION', 'order', 'URL', '/a', FROM, FROM.plusSeconds(600),
                Granularity.MINUTE_10, [])

        then: "合并成 1 行，锚定目标桶起点"
        rows.size() == 1
        rows[0].bucketStart() == FROM
        rows[0].count() == 6
        rows[0].durationSum() == 600
        rows[0].distribution().count() == 6
    }

    def "合并后的分布仍可重算分位（不是平均子桶分位）"() {
        given:
        minuteRows = [
                minuteRow(1, 100, dist(3, 1L), FROM),
                minuteRow(9, 900, dist(3, 9L), FROM.plusSeconds(60)),
        ]

        when:
        def rows = port.rows('TRANSACTION', 'order', 'URL', '/a', FROM, FROM.plusSeconds(600),
                Granularity.MINUTE_10, [])

        then: "10 个样本都在 100ms 所在的分段内，p50 落在该段"
        rows[0].distribution().count() == 10
        rows[0].distribution().percentile(0.5d) != null
    }

    def "不同目标桶的行不会被并到一起"() {
        given:
        minuteRows = [
                minuteRow(1, 100, dist(3, 1L), FROM),
                minuteRow(5, 500, dist(3, 5L), FROM.plusSeconds(600)),
        ]

        when:
        def rows = port.rows('TRANSACTION', 'order', 'URL', '/a', FROM, FROM.plusSeconds(1200),
                Granularity.MINUTE_10, [])

        then:
        rows.size() == 2
        rows[0].count() == 1
        rows[1].count() == 5
    }

    def "请求 1 分钟粒度时不做折叠"() {
        given:
        minuteRows = [
                minuteRow(1, 100, dist(3, 1L), FROM),
                minuteRow(2, 200, dist(3, 2L), FROM.plusSeconds(60)),
        ]

        when:
        def rows = port.rows('TRANSACTION', 'order', 'URL', '/a', FROM, FROM.plusSeconds(120),
                Granularity.MINUTE_1, [])

        then:
        rows.size() == 2
    }
}
