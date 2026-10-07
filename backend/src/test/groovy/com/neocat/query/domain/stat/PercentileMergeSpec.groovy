package com.neocat.query.domain.stat

import com.neocat.analysis.domain.bucket.AggregatedRow
import com.neocat.analysis.domain.bucket.AggregationLevel
import com.neocat.analysis.domain.bucket.DurationDistribution
import com.neocat.analysis.domain.bucket.SeriesKey
import com.neocat.analysis.domain.bucket.SeriesKind
import spock.lang.Specification
import spock.lang.Unroll

import java.time.Instant

/**
 * G8 任务53（红）：分位合并。
 * 对应 PRD 03 §3（「tp50–tp9999 合并原始耗时分布后重新计算」「不能平均各子桶的分位」）、
 * PRD 04 §9（「分位基于桶内原始数值分布，不平均子桶分位」）。
 */
class PercentileMergeSpec extends Specification {

    PercentileMerger merger = new PercentileMerger()

    static Instant HOUR = Instant.parse("2026-09-24T04:00:00Z")

    def rowWithDurations(String instance, List<Long> durations) {
        def key = SeriesKey.of("order", SeriesKind.TRANSACTION, "URL", "/a", instance)
        def row = new AggregatedRow(key, HOUR, AggregationLevel.MINUTE, 60)
        long sum = 0
        long min = Long.MAX_VALUE
        long max = Long.MIN_VALUE
        durations.each { d ->
            sum += d
            min = Math.min(min, d)
            max = Math.max(max, d)
            row.recordDuration(d)
        }
        row.addCount(durations.size(), 0, sum, min, max)
        return row
    }

    // ── 合并顺序正确性 ───────────────────────────────────────

    def "合并分布后重算分位：结果远大于「平均子桶分位」的错误值"() {
        given: "机器 A：10 次 10ms；机器 B：90 次 1000ms"
        def a = rowWithDurations("10.0.0.8", (1..10).collect { 10L })
        def b = rowWithDurations("10.0.0.9", (1..90).collect { 1000L })

        when:
        def p50 = merger.percentile([a, b], 0.50d)

        then: "正确值落在 1000ms 段"
        p50 >= 1000.0d

        and: "错误做法（平均两台机器的 p50 = (10+1000)/2 = 505）明显更小"
        p50 > 505.0d
    }

    def "样本总量与分布段数一致"() {
        given:
        def a = rowWithDurations("10.0.0.8", (1..10).collect { 10L })
        def b = rowWithDurations("10.0.0.9", (1..90).collect { 1000L })

        when:
        def dist = merger.mergeDistribution([a, b])

        then:
        dist.count() == 100
        dist.segments().sum() == 100
    }

    def "单行时各分位与单行自身计算一致"() {
        given:
        def row = rowWithDurations("10.0.0.8", (1..100).collect { it * 10L })

        expect:
        merger.percentile([row], 0.50d) == row.distribution().percentile(0.50d)
    }

    def "同一时间桶只有一个值时各分位与该值相同"() {
        given:
        def row = rowWithDurations("10.0.0.8", [42L])

        expect:
        merger.percentile([row], 0.50d) == 42.0d
        merger.percentile([row], 0.99d) == 42.0d
        merger.percentile([row], 0.9999d) == 42.0d
    }

    // ── 低基数精确值 ─────────────────────────────────────────

    def "样本量在精确值上限内时使用精确分位（零误差）"() {
        given: "合计 20 个样本，远低于上限 200"
        def a = rowWithDurations("10.0.0.8", (1..10).collect { it * 7L })
        def b = rowWithDurations("10.0.0.9", (11..20).collect { it * 7L })

        when:
        def p50 = merger.percentile([a, b], 0.50d)

        then: "精确值：20 个样本的 p50 = 第 10 个（70ms）"
        p50 == 70.0d
    }

    def "合并后的样本量超过精确值上限时退化为分箱估算"() {
        given: "两侧各 150 个样本，合计 300 > 200"
        def a = rowWithDurations("10.0.0.8", (1..150).collect { 100L })
        def b = rowWithDurations("10.0.0.9", (1..150).collect { 100L })

        when:
        def dist = merger.mergeDistribution([a, b])
        def p50 = merger.percentile([a, b], 0.50d)

        then: "分布总数正确"
        dist.count() == 300

        and: "分位仍落在合理区间（100ms 附近）"
        p50 >= 64.0d && p50 <= 128.0d
    }

    // ── 跨桶合并 ─────────────────────────────────────────────

    def "跨桶合并不受行顺序影响"() {
        given:
        def a = rowWithDurations("10.0.0.8", (1..10).collect { 10L })
        def b = rowWithDurations("10.0.0.9", (1..90).collect { 1000L })

        expect:
        merger.percentile([a, b], 0.99d) == merger.percentile([b, a], 0.99d)
    }

    def "分位单调不减"() {
        given:
        def rows = [
                rowWithDurations("10.0.0.8", (1..50).collect { 10L }),
                rowWithDurations("10.0.0.9", (1..50).collect { 5000L })
        ]

        when:
        def p = merger.percentiles(rows)

        then:
        p.getTp50() <= p.getTp90()
        p.getTp90() <= p.getTp95()
        p.getTp95() <= p.getTp99()
        p.getTp99() <= p.getTp999()
        p.getTp999() <= p.getTp9999()
    }

    // ── 无样本 ───────────────────────────────────────────────

    def "无行时分位为 null（缺数不等于零）"() {
        expect:
        merger.percentile([], 0.99d) == null
    }

    def "所有行都无样本时分位为 null"() {
        given:
        def empty = rowWithDurations("10.0.0.8", [])

        expect:
        merger.percentile([empty], 0.99d) == null
        merger.mergeDistribution([empty]).count() == 0
    }

    def "确认无调用（count=0）时全部标准分位为 null"() {
        given: "一行存在但没有任何耗时样本"
        def key = SeriesKey.of("order", SeriesKind.TRANSACTION, "URL", "/a", "all")
        def row = new AggregatedRow(key, HOUR, AggregationLevel.MINUTE, 60)
        row.addCount(0, 0, 0, 0, 0)

        when:
        def p = merger.percentiles([row])

        then:
        p.getTp50() == null
        p.getTp90() == null
        p.getTp95() == null
        p.getTp99() == null
        p.getTp999() == null
        p.getTp9999() == null
    }

    @Unroll
    def "标准分位集合覆盖设计要求的 #label"() {
        given:
        def rows = [rowWithDurations("10.0.0.8", (1..100).collect { it * 3L })]

        when:
        def p = merger.percentiles(rows)

        then:
        p."$getter"() != null

        where:
        label  | getter
        "tp50"  | "getTp50"
        "tp90"  | "getTp90"
        "tp95"  | "getTp95"
        "tp99"  | "getTp99"
        "tp999" | "getTp999"
        "tp9999"| "getTp9999"
    }
}
