package com.neocat.query.infra.datasource

import com.neocat.analysis.domain.bucket.SeriesKey
import com.neocat.analysis.domain.bucket.SeriesKind
import com.neocat.analysis.infra.store.InMemoryHourlyReportStore
import com.neocat.common.time.bucket.DefaultTimeBucketResolver
import com.neocat.common.time.bucket.Granularity
import spock.lang.Specification

import java.time.Instant
import java.time.ZoneOffset

/**
 * 「范围长度」与「请求粒度」分离后的端到端口径（技术方案 03 §4.2）。
 *
 * <p>回归的是这样一类请求：**范围较长，但要求较细的点**，例如「最近 24 小时、每 10 分钟一个点」。
 * 旧实现按范围长度推断粒度（24 小时 → 1 小时桶），调用方按 10 分钟桶起点取数时
 * 只能命中每 6 个桶里的 1 个，其余 5 个变成缺口 —— 图上一片空白且不报错。
 *
 * <p>这些用例锁定：只要调用方明确说了粒度，读取侧就必须按该粒度对齐返回。
 */
class GranularityContractSpec extends Specification {

    static final Instant BASE = Instant.parse('2026-09-24T04:00:00Z')

    InMemoryHourlyReportStore store
    HourlyReportDataPort port
    SeriesKey key

    def setup() {
        store = new InMemoryHourlyReportStore()
        port = new HourlyReportDataPort(store, new DefaultTimeBucketResolver(), { ZoneOffset.UTC })
        key = SeriesKey.of('order', SeriesKind.TRANSACTION, 'URL', '/a')
    }

    /** 把某一分钟的调用写进内存。 */
    void record(Instant at, long duration = 100) {
        store.add(key, at, duration, false)
    }

    def "长范围 + 细粒度：每个目标桶都能取到落在其中的数据"() {
        given: "一小时里每隔 10 分钟有一次调用"
        (0..5).each { record(BASE.plusSeconds(it * 600L)) }

        when: "按 10 分钟粒度取这一小时"
        def rows = port.rows('TRANSACTION', 'order', 'URL', '/a', BASE, BASE.plusSeconds(3600),
                Granularity.MINUTE_10, [])

        then: "6 个 10 分钟桶各有一行，而不是只有边界上的少数几个"
        rows.size() == 6
        rows.every { it.count() == 1 }
        rows*.bucketStart == (0..5).collect { BASE.plusSeconds(it * 600L) }
    }

    def "范围长度不参与粒度判定：1 小时范围按小时取只有 1 个点"() {
        given:
        (0..5).each { record(BASE.plusSeconds(it * 600L)) }

        when:
        def rows = port.rows('TRANSACTION', 'order', 'URL', '/a', BASE, BASE.plusSeconds(3600),
                Granularity.HOUR_1, [])

        then:
        rows.size() == 1
        rows[0].bucketStart() == BASE
        rows[0].count() == 6
    }

    def "同一份数据在两种粒度下的总次数一致（折叠不丢数）"() {
        given:
        (0..5).each { record(BASE.plusSeconds(it * 600L)) }

        when:
        def fine = port.rows('TRANSACTION', 'order', 'URL', '/a', BASE, BASE.plusSeconds(3600),
                Granularity.MINUTE_10, [])
        def coarse = port.rows('TRANSACTION', 'order', 'URL', '/a', BASE, BASE.plusSeconds(3600),
                Granularity.HOUR_1, [])

        then: "折叠只是改变点的疏密，不改变总量"
        fine.sum { it.count() } == coarse.sum { it.count() }
        fine.sum { it.count() } == 6
    }

    def "同一份数据在两种粒度下的分位都可计算（分布随折叠一起搬）"() {
        given:
        (0..5).each { record(BASE.plusSeconds(it * 600L)) }

        when:
        def fine = port.rows('TRANSACTION', 'order', 'URL', '/a', BASE, BASE.plusSeconds(3600),
                Granularity.MINUTE_10, [])
        def coarse = port.rows('TRANSACTION', 'order', 'URL', '/a', BASE, BASE.plusSeconds(3600),
                Granularity.HOUR_1, [])

        then:
        fine.every { it.distribution().count() == 1 }
        coarse[0].distribution().count() == 6
        coarse[0].distribution().percentile(0.5d) == 100.0d
    }
}
