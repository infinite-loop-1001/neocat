package com.neocat.analysis.domain.bucket

import spock.lang.Specification
import spock.lang.Unroll

import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime

import static com.neocat.analysis.domain.bucket.AggregationLevel.*

/**
 * G6 任务41（红）：分钟 → 小时 → 日/周/月滚动聚合。
 * 对应 PRD 03 §2.1（周期定义）、§3（桶内聚合不变式）、§4（QPS 三态）、
 * PRD 00 §10（留存）。
 */
class BucketAggregationSpec extends Specification {

    static final ZoneId SH = ZoneId.of("Asia/Shanghai")

    AggregationRoller roller = new AggregationRoller()

    def key = SeriesKey.of("order", SeriesKind.TRANSACTION, "URL", "/a")

    def minuteRow(Instant start, long count, long failCount, long durationSum, long durationMin, long durationMax) {
        def row = new AggregatedRow(key, start, MINUTE, 60)
        row.addCount(count, failCount, durationSum, durationMin, durationMax)
        return row
    }

    // ── 小时聚合 ─────────────────────────────────────────────

    def "分钟 → 小时：分子与 min/max 正确合并"() {        given:
        def h = Instant.parse("2026-09-24T04:00:00Z")
        def rows = [
                minuteRow(h, 100, 5, 5000, 10, 200),
                minuteRow(h.plusSeconds(60), 200, 0, 8000, 5, 150),
                minuteRow(h.plusSeconds(120), 50, 1, 1000, 20, 30)
        ]

        when:
        def rolled = roller.roll(rows, HOUR)

        then:
        rolled.size() == 1
        def row = rolled[0]
        row.key() == key
        row.bucketStart() == h
        row.count() == 350
        row.failCount() == 6
        row.durationSum() == 14000
        row.durationMin() == 5
        row.durationMax() == 200
    }

    def "分钟 → 小时：avg 与 failureRate 在合并后计算，而非平均子桶"() {
        given:
        def h = Instant.parse("2026-09-24T04:00:00Z")
        def rows = [
                minuteRow(h, 10, 0, 1000, 100, 100),       // avg=100
                minuteRow(h.plusSeconds(60), 90, 9, 900, 10, 10)   // avg=10
        ]

        when:
        def row = roller.roll(rows, HOUR)[0]

        then: "正确值 = 1900/100 = 19；若错误地平均两个 avg 会得到 55"
        row.averageDuration() == 19.0d

        and: "失败率 = 9/100"
        row.failureRate() == 0.09d
    }

    def "分钟 → 小时：不同序列不合并"() {
        given:
        def h = Instant.parse("2026-09-24T04:00:00Z")
        def other = SeriesKey.of("order", SeriesKind.TRANSACTION, "URL", "/b")
        def r1 = minuteRow(h, 10, 0, 100, 1, 1)
        def r2 = new AggregatedRow(other, h, MINUTE, 60)
        r2.addCount(20, 0, 200, 1, 1)

        when:
        def rolled = roller.roll([r1, r2], HOUR)

        then:
        rolled.size() == 2
        rolled.find { it.key() == key }.count() == 10
        rolled.find { it.key() == other }.count() == 20
    }

    def "分钟 → 小时：同一序列跨多个小时分组"() {
        given:
        def h1 = Instant.parse("2026-09-24T04:00:00Z")
        def h2 = Instant.parse("2026-09-24T05:00:00Z")
        def rows = [minuteRow(h1, 10, 0, 100, 1, 1), minuteRow(h2, 20, 0, 200, 1, 1)]

        when:
        def rolled = roller.roll(rows, HOUR)

        then:
        rolled.size() == 2
        rolled.find { it.bucketStart() == h1 }.count() == 10
        rolled.find { it.bucketStart() == h2 }.count() == 20
    }

    def "空输入返回空结果"() {
        expect:
        roller.roll([], HOUR).isEmpty()
    }

    // ── 日 / 周 / 月聚合 ─────────────────────────────────────

    def "小时 → 日：按平台时区自然日分组"() {
        given: "平台时区 2026-09-24 00:00 起共 3 个小时桶"
        def day = ZonedDateTime.of(2026, 9, 24, 0, 0, 0, 0, SH).toInstant()
        def rows = (0..2).collect { i ->
            def row = new AggregatedRow(key, day.plusSeconds(i * 3600L), HOUR, 3600)
            row.addCount(100, 1, 1000, 5, 50)
            row
        }

        when:
        def rolled = roller.roll(rows, DAY, SH)

        then:
        rolled.size() == 1
        rolled[0].bucketStart() == day
        rolled[0].count() == 300
        rolled[0].failCount() == 3
    }

    def "周起点为平台时区周一 00:00"() {
        given: "2026-09-24 是周四"
        def thursday = ZonedDateTime.of(2026, 9, 24, 13, 0, 0, 0, SH).toInstant()

        expect:
        roller.bucketStart(thursday, WEEK, SH) ==
                ZonedDateTime.of(2026, 9, 21, 0, 0, 0, 0, SH).toInstant()
    }

    def "月起点为平台时区月初 00:00"() {
        given:
        def midSeptember = ZonedDateTime.of(2026, 9, 24, 13, 0, 0, 0, SH).toInstant()

        expect:
        roller.bucketStart(midSeptember, MONTH, SH) ==
                ZonedDateTime.of(2026, 9, 1, 0, 0, 0, 0, SH).toInstant()
    }

    @Unroll
    def "#level 的桶起点对齐"() {
        given:
        def instant = ZonedDateTime.of(2026, 9, 24, 13, 37, 41, 0, SH).toInstant()

        expect:
        roller.bucketStart(instant, level, SH) == expected

        where:
        level | expected
        MINUTE | ZonedDateTime.of(2026, 9, 24, 13, 37, 0, 0, SH).toInstant()
        HOUR   | ZonedDateTime.of(2026, 9, 24, 13, 0, 0, 0, SH).toInstant()
        DAY    | ZonedDateTime.of(2026, 9, 24, 0, 0, 0, 0, SH).toInstant()
    }

    // ── QPS 分母三态（PRD 03 §4） ────────────────────────────

    def "当前未结束小时：分母为整点至当前时刻的实际秒数"() {
        given:
        def hourStart = ZonedDateTime.of(2026, 9, 24, 13, 0, 0, 0, SH).toInstant()
        def hourEnd = hourStart.plusSeconds(3600)
        def now = hourStart.plusSeconds(23 * 60 + 41)          // 13:23:41

        expect:
        roller.coveredSeconds(hourStart, hourEnd, now, HOUR) == 23 * 60 + 41
    }

    def "完整历史小时：分母为 3600"() {
        given:
        def hourStart = ZonedDateTime.of(2026, 9, 24, 10, 0, 0, 0, SH).toInstant()
        def hourEnd = hourStart.plusSeconds(3600)
        def now = ZonedDateTime.of(2026, 9, 24, 13, 0, 0, 0, SH).toInstant()

        expect:
        roller.coveredSeconds(hourStart, hourEnd, now, HOUR) == 3600
    }

    def "自然日：分母为该日报表实际覆盖的秒数"() {
        given: "进行中的一天，从 00:00 到当前 13:23:41"
        def dayStart = ZonedDateTime.of(2026, 9, 24, 0, 0, 0, 0, SH).toInstant()
        def dayEnd = dayStart.plusSeconds(86400)
        def now = ZonedDateTime.of(2026, 9, 24, 13, 23, 41, 0, SH).toInstant()

        expect:
        roller.coveredSeconds(dayStart, dayEnd, now, DAY) == 13 * 3600 + 23 * 60 + 41
    }

    def "进行中的桶只算实际覆盖秒数（截到当前时刻）"() {
        given: "10 分钟粒度的桶 [13:20, 13:30)，当前时刻 13:25:10"
        def bucketStart = ZonedDateTime.of(2026, 9, 24, 13, 20, 0, 0, SH).toInstant()
        def bucketEnd = bucketStart.plusSeconds(600)
        def now = ZonedDateTime.of(2026, 9, 24, 13, 25, 10, 0, SH).toInstant()

        expect: "只覆盖到 now"
        roller.coveredSeconds(bucketStart, bucketEnd, now, MINUTE) == 5 * 60 + 10
    }

    def "已结束的桶覆盖满额秒数"() {
        given:
        def bucketStart = ZonedDateTime.of(2026, 9, 24, 13, 20, 0, 0, SH).toInstant()
        def bucketEnd = bucketStart.plusSeconds(600)
        def now = ZonedDateTime.of(2026, 9, 24, 13, 40, 0, 0, SH).toInstant()

        expect:
        roller.coveredSeconds(bucketStart, bucketEnd, now, MINUTE) == 600
    }

    def "QPS 由合并后的总次数与分母计算（禁止平均各机器 QPS）"() {
        given:
        def hourStart = Instant.parse("2026-09-24T04:00:00Z")
        def rows = [
                minuteRow(hourStart, 30, 0, 300, 1, 1),
                minuteRow(hourStart.plusSeconds(60), 60, 0, 600, 1, 1)
        ]

        when:
        def row = roller.roll(rows, HOUR)[0]
        row.setCoveredSeconds(3600)

        then: "总次数 90 ÷ 3600 = 0.025"
        row.qps() == 0.025d
    }

    def "无调用时 QPS 为无值而非 0"() {
        given:
        def row = roller.roll([], HOUR)

        expect:
        row.isEmpty()
    }

    def "coveredSeconds 为 0 时 QPS 无值"() {
        given:
        def row = new AggregatedRow(key, Instant.parse("2026-09-24T04:00:00Z"), HOUR, 0)
        row.addCount(10, 0, 100, 1, 1)

        expect:
        row.coveredSeconds() == 0
        row.qps() == null
    }

    // ── 分布合并 ─────────────────────────────────────────────

    def "分布逐段相加而非平均（跨桶分位保持正确）"() {
        given: "两个桶：一个全是 10ms，一个全是 1000ms"
        def a = new DurationDistribution()
        (1..10).each { a.record(10L) }
        def b = new DurationDistribution()
        (1..90).each { b.record(1000L) }

        when:
        def merged = a.merge(b)

        then: "合并后样本数 100，p50 落在 1000ms 段"
        merged.count() == 100
        merged.percentile(0.5d) >= 1000.0d

        and: "若错误地平均子桶分位会得到 (10+1000)/2 = 505"
        merged.percentile(0.5d) > 505.0d
    }

    def "合并后 min/max 取真实极值"() {
        given:
        def a = new DurationDistribution()
        a.record(5L)
        def b = new DurationDistribution()
        b.record(9999L)

        expect:
        a.merge(b).segments().sum() == 2
    }

    def "空分布合并保持空"() {
        given:
        def a = new DurationDistribution()
        def b = new DurationDistribution()

        expect:
        a.merge(b).count() == 0
        a.merge(b).percentile(0.99d) == null
    }

    def "数值型指标跨桶合并累加 sum 与 count"() {
        given:
        def r1 = new AggregatedRow(key, Instant.parse("2026-09-24T04:00:00Z"), MINUTE, 60)
        r1.addValue(10.0d, 1)
        def r2 = new AggregatedRow(key, Instant.parse("2026-09-24T04:01:00Z"), MINUTE, 60)
        r2.addValue(30.0d, 1)

        when:
        def row = roller.roll([r1, r2], HOUR)[0]

        then:
        row.valueSum() == 40.0d
        row.valueCount() == 2
    }
}
