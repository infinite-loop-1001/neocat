package com.neocat.analysis.domain.bucket

import spock.lang.Specification

import java.time.Instant
import java.time.ZoneOffset

import static com.neocat.analysis.domain.bucket.AggregationLevel.HOUR
import static com.neocat.analysis.domain.bucket.AggregationLevel.MINUTE

/**
 * 滚动聚合对**耗时分布**的合并（PRD 03 §3、技术方案 06 §9）。
 *
 * <p>为什么单独立一组：{@code BucketAggregationSpec} 只覆盖了分子与 min/max，
 * 分布没被断言过。而「先合并原始分布、再算分位」是分位正确性的唯一保证
 * （PRD 03 §3 明确禁止平均子桶分位）。分布一旦在滚动聚合中丢失，
 * 小时/日/周/月层级的 tp* 会全部变成无值，且不会报错。
 */
class RollupDistributionSpec extends Specification {

    AggregationRoller roller = new AggregationRoller()

    def key = SeriesKey.of("order", SeriesKind.TRANSACTION, "URL", "/a")

    /** 造一个带耗时样本的分钟行。 */
    def minuteRow(Instant start, List<Long> durations) {
        def row = new AggregatedRow(key, start, MINUTE, 60)
        long sum = durations.sum(0L) { it }
        row.addCount(durations.size(), 0, sum, durations.min(), durations.max())
        durations.each { row.distribution().record(it) }
        return row
    }

    // ── 分布必须随滚动聚合一起合并 ───────────────────────────

    def "分钟 → 小时：分布样本数等于各分钟之和"() {
        given:
        def h = Instant.parse("2026-09-24T04:00:00Z")
        def rows = [
                minuteRow(h, [100L, 200L]),
                minuteRow(h.plusSeconds(60), [300L, 400L, 500L])
        ]

        when:
        def row = roller.roll(rows, HOUR)[0]

        then: "分布不能丢；丢了分位就查不出来"
        row.distribution().count() == 5
    }

    def "分钟 → 小时：分位可从合并后的分布重算"() {
        given:
        def h = Instant.parse("2026-09-24T04:00:00Z")
        def rows = [
                minuteRow(h, [10L, 20L, 30L, 40L]),
                minuteRow(h.plusSeconds(60), [1000L, 2000L, 3000L, 4000L])
        ]

        when:
        def row = roller.roll(rows, HOUR)[0]

        then: "8 个样本，p50 = 第 4 个 = 40"
        row.distribution().percentile(0.5d) == 40.0d

        and: "不是 null —— null 意味着这条链路的 tp* 永远是空值"
        row.distribution().percentile(0.99d) != null
    }

    def "小时 → 日：跨小时合并不平均子桶分位"() {
        given:
        def dayStart = Instant.parse("2026-09-24T00:00:00Z")
        // 第一个小时 10 次 1000ms，第二个小时 90 次 10ms
        def h1 = new AggregatedRow(key, dayStart, HOUR, 3600)
        h1.addCount(10, 0, 10_000, 1000, 1000)
        10.times { h1.distribution().record(1000) }
        def h2 = new AggregatedRow(key, dayStart.plusSeconds(3600), HOUR, 3600)
        h2.addCount(90, 0, 900, 10, 10)
        90.times { h2.distribution().record(10) }

        when:
        def row = roller.roll([h1, h2], com.neocat.analysis.domain.bucket.AggregationLevel.DAY, ZoneOffset.UTC)[0]

        then: "100 个样本，p50 落在大量 10ms 样本上"
        row.distribution().count() == 100
        row.distribution().percentile(0.5d) == 10.0d

        and: "错误实现（平均两个子桶分位）会得到 (1000+10)/2 = 505"
        row.distribution().percentile(0.5d) < 505.0d
    }

    def "空分布参与合并不产生伪分位"() {
        given:
        def h = Instant.parse("2026-09-24T04:00:00Z")
        def withData = minuteRow(h, [100L])
        def countOnly = new AggregatedRow(key, h.plusSeconds(60), MINUTE, 60)
        countOnly.addCount(5, 0, 0, 0, 0)

        when:
        def row = roller.roll([withData, countOnly], HOUR)[0]

        then: "只计数不记耗时的行不贡献样本"
        row.count() == 6
        row.distribution().count() == 1
        row.distribution().percentile(0.99d) == 100.0d
    }
}
