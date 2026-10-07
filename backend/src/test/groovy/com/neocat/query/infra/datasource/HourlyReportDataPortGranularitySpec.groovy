package com.neocat.query.infra.datasource

import com.neocat.analysis.domain.bucket.AggregationLevel
import com.neocat.analysis.domain.bucket.SeriesKey
import com.neocat.analysis.domain.bucket.SeriesKind
import com.neocat.analysis.infra.store.InMemoryHourlyReportStore
import com.neocat.common.time.bucket.DefaultTimeBucketResolver
import com.neocat.common.time.bucket.Granularity
import spock.lang.Specification

import java.time.Instant
import java.time.ZoneOffset

/**
 * 内存报表读取的**粒度折叠**（技术方案 03 §4.1、§4.2）。
 *
 * <p>为什么必须锁定：内存里只存分钟桶，而调用方可能按 5/10/20 分钟或整小时取点。
 * 实现若只按「目标桶起点」查一个分钟桶，其余分钟的数据会被静默丢掉 ——
 * 页面不报错，只是数字偏小若干倍，这是最难发现的一类缺陷。
 */
class HourlyReportDataPortGranularitySpec extends Specification {

    static final Instant HOUR = Instant.parse('2026-09-24T04:00:00Z')

    InMemoryHourlyReportStore store
    HourlyReportDataPort port
    SeriesKey key

    def setup() {
        store = new InMemoryHourlyReportStore()
        port = new HourlyReportDataPort(store, new DefaultTimeBucketResolver(), { ZoneOffset.UTC })
        key = SeriesKey.of('order', SeriesKind.TRANSACTION, 'URL', '/a')
    }

    /** 在第 n 分钟写入一次 100ms 调用。 */
    void recordMinute(int minuteOffset, int count = 1) {
        count.times {
            store.add(key, HOUR.plusSeconds(minuteOffset * 60L), 100, false)
        }
    }

    // ── 折叠：一小时的分钟数据要卷成一个小时点 ───────────────

    def "请求小时粒度时，桶起点锚定目标桶而非首个有数据的分钟"() {
        given: "第 10 分钟才有数据（不是整点）"
        recordMinute(10)

        when:
        def rows = port.rows('TRANSACTION', 'order', 'URL', '/a', HOUR, HOUR.plusSeconds(3600),
                Granularity.HOUR_1, [])

        then: "锚点必须是 04:00，否则调用方按桶起点取不到"
        rows.size() == 1
        rows[0].bucketStart() == HOUR
    }

    def "请求小时粒度时，桶内所有分钟的次数被累加而不是只取第一个"() {
        given: "第 0/1/2 分钟各一次，第 30 分钟两次"
        recordMinute(0)
        recordMinute(1)
        recordMinute(2)
        recordMinute(30, 2)

        when:
        def rows = port.rows('TRANSACTION', 'order', 'URL', '/a', HOUR, HOUR.plusSeconds(3600),
                Granularity.HOUR_1, [])

        then: "1+1+1+2 = 5；只取一个分钟桶会得到 1"
        rows.size() == 1
        rows[0].count() == 5
        rows[0].durationSum() == 500
    }

    def "请求小时粒度时，分布随之合并（分位不丢）"() {
        given:
        recordMinute(0, 3)
        recordMinute(20, 7)

        when:
        def rows = port.rows('TRANSACTION', 'order', 'URL', '/a', HOUR, HOUR.plusSeconds(3600),
                Granularity.HOUR_1, [])

        then: "10 个耗时样本进入同一份分布"
        rows[0].distribution().count() == 10
        rows[0].distribution().percentile(0.5d) == 100.0d
    }

    def "请求 10 分钟粒度时，只卷桶内那 10 分钟"() {
        given: "第 0 分钟与第 30 分钟各一次"
        recordMinute(0)
        recordMinute(30)

        when: "第一个 10 分钟桶"
        def first = port.rows('TRANSACTION', 'order', 'URL', '/a', HOUR, HOUR.plusSeconds(600),
                Granularity.MINUTE_10, [])
        and: "覆盖第 30 分钟的桶"
        def later = port.rows('TRANSACTION', 'order', 'URL', '/a',
                HOUR.plusSeconds(1800), HOUR.plusSeconds(2400), Granularity.MINUTE_10, [])

        then: "各自只包含落在自己区间内的分钟"
        first.size() == 1
        first[0].bucketStart() == HOUR
        first[0].count() == 1
        later.size() == 1
        later[0].count() == 1
    }

    def "桶内完全没有数据时不产生行（缺口而非 0）"() {
        when:
        def rows = port.rows('TRANSACTION', 'order', 'URL', '/a', HOUR, HOUR.plusSeconds(3600),
                Granularity.HOUR_1, [])

        then:
        rows.isEmpty()
    }

    def "分钟粒度下不折叠，逐分钟返回"() {
        given:
        recordMinute(0)
        recordMinute(1)

        when:
        def rows = port.rows('TRANSACTION', 'order', 'URL', '/a', HOUR, HOUR.plusSeconds(120),
                Granularity.MINUTE_1, [])

        then:
        rows.size() == 2
        rows[0].bucketStart() == HOUR
        rows[1].bucketStart() == HOUR.plusSeconds(60)
    }

    def "只有数值没有次数的行（Metric）也参与折叠"() {
        given:
        def metricKey = SeriesKey.metric('order', 'order.amount', 'city=上海;')
        store.addValue(metricKey, HOUR.plusSeconds(60), 10.0d)
        store.addValue(metricKey, HOUR.plusSeconds(120), 32.0d)

        when:
        def rows = port.rows('METRIC', 'order', 'order.amount', 'city=上海;',
                HOUR, HOUR.plusSeconds(3600), Granularity.HOUR_1, [])

        then:
        rows.size() == 1
        rows[0].valueSum() == 42.0d
        rows[0].valueCount() == 2
    }
}
