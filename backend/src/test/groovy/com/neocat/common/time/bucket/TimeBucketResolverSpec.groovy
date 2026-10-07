package com.neocat.common.time.bucket

import com.neocat.common.time.range.RangeQuick
import com.neocat.common.time.range.RangeSpec

import spock.lang.Specification
import spock.lang.Unroll

import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId

/**
 * G0 任务1（红）+ G8 任务47：时间范围与桶解析契约。
 * 对应 PRD 03 §2.1 / §2.2 / §2.3 —— 粒度、边界、左闭右开、部分覆盖。
 */
class TimeBucketResolverSpec extends Specification {

    static final ZoneId SH = ZoneId.of("Asia/Shanghai")
    TimeBucketResolver resolver = new DefaultTimeBucketResolver()

    def "小时范围：1 分钟/点，共 60 个完整桶"() {
        given:
        def hourStart = Instant.parse("2026-09-24T02:00:00Z")   // 平台时区 10:00

        when:
        def buckets = resolver.resolve(new RangeSpec.Hour(hourStart), SH)

        then:
        buckets.size() == 60
        buckets[0].getStart() == hourStart
        buckets[0].getEnd() == Instant.parse("2026-09-24T02:01:00Z")
        buckets[0].isPartial() == false
        buckets[0].getCoveredSeconds() == 60
        buckets[59].getStart() == Instant.parse("2026-09-24T02:59:00Z")
        buckets[59].getEnd() == Instant.parse("2026-09-24T03:00:00Z")
    }

    def "自然日范围：10 分钟/点，共 144 个桶"() {
        when:
        def buckets = resolver.resolve(new RangeSpec.Day(LocalDate.of(2026, 9, 24)), SH)

        then:
        buckets.size() == 144
        buckets[0].getStart() == Instant.parse("2026-09-23T16:00:00Z")   // 平台时区 00:00
        buckets[0].getCoveredSeconds() == 600
        buckets[143].getStart() == Instant.parse("2026-09-24T15:50:00Z")
        buckets[143].getEnd() == Instant.parse("2026-09-24T16:00:00Z")
    }

    def "自然周范围：1 小时/点，从平台时区周一 00:00 起共 168 个桶"() {
        when:
        // 2026-09-24 是周四，所在周周一为 2026-09-21
        def buckets = resolver.resolve(new RangeSpec.Week(LocalDate.of(2026, 9, 24)), SH)

        then:
        buckets.size() == 168
        buckets[0].getStart() == Instant.parse("2026-09-20T16:00:00Z")   // 平台时区周一 00:00
        buckets[0].getCoveredSeconds() == 3600
        buckets[167].getStart() == Instant.parse("2026-09-27T15:00:00Z")
        buckets[167].getEnd() == Instant.parse("2026-09-27T16:00:00Z")
    }

    def "自然月范围：1 自然日/点，9 月共 30 个桶"() {
        when:
        def buckets = resolver.resolve(new RangeSpec.Month(YearMonth.of(2026, 9)), SH)

        then:
        buckets.size() == 30
        buckets[0].getStart() == Instant.parse("2026-08-31T16:00:00Z")
        buckets[0].getCoveredSeconds() == 86400
        buckets[29].getEnd() == Instant.parse("2026-09-30T16:00:00Z")
    }

    @Unroll
    def "快捷范围 #quick 使用 #granularity 粒度"() {
        given:
        def now = Instant.parse("2026-09-24T04:23:41Z")   // 平台时区 12:23:41

        when:
        def buckets = resolver.resolve(new RangeSpec.QuickRange(quick, now), SH)
        def expectedCount = Math.ceil(
                java.time.Duration.between(buckets[0].getStart(), now).getSeconds()
                        / (double) granularity.seconds()) as int

        then:
        buckets.size() == expectedCount
        buckets[0].isPartial() == true
        buckets[-1].isPartial() == true
        buckets[0].getCoveredSeconds() < granularity.seconds()

        where:
        quick                    | granularity
        RangeQuick.RECENT_1H  | Granularity.MINUTE_1
        RangeQuick.RECENT_3H  | Granularity.MINUTE_5
        RangeQuick.RECENT_6H  | Granularity.MINUTE_10
        RangeQuick.RECENT_12H | Granularity.MINUTE_20
        RangeQuick.RECENT_24H | Granularity.HOUR_1
    }

    def "今天范围：按平台时区 00:00 起，粒度 10 分钟"() {
        given:
        def now = Instant.parse("2026-09-24T04:23:41Z")   // 平台时区 12:23:41

        when:
        def buckets = resolver.resolve(new RangeSpec.QuickRange(RangeQuick.TODAY, now), SH)

        then:
        buckets[0].getStart() == Instant.parse("2026-09-23T16:00:00Z")
        buckets[0].getCoveredSeconds() == 600
        // 12:23 → 0:00 起共 74 个已开始的 10 分钟点
        buckets.size() == 75
        // 末桶为进行中，部分覆盖
        buckets[-1].isPartial() == true
        buckets[-1].getCoveredSeconds() == 3 * 60 + 41         // 12:20:00–12:23:41
    }

    def "滚动范围桶边界对齐平台时区固定边界，而非对齐 now"() {
        given:
        def now = Instant.parse("2026-09-24T04:23:41Z")   // 平台时区 12:23:41

        when:
        def buckets = resolver.resolve(new RangeSpec.QuickRange(RangeQuick.RECENT_1H, now), SH)

        then:
        // 1 分钟粒度边界天然对齐；末桶起点为 12:23:00
        buckets[-1].getStart() == Instant.parse("2026-09-24T04:23:00Z")
        buckets[-1].getEnd() == Instant.parse("2026-09-24T04:24:00Z")
    }

    def "20 分钟粒度的桶边界对齐平台时区固定边界（12:20 起）"() {
        given:
        def now = Instant.parse("2026-09-24T04:23:41Z")   // 平台时区 12:23:41

        when:
        def buckets = resolver.resolve(new RangeSpec.QuickRange(RangeQuick.RECENT_12H, now), SH)

        then:
        buckets[-1].getStart() == Instant.parse("2026-09-24T04:20:00Z")
        buckets[-1].getCoveredSeconds() == 3 * 60 + 41        // 12:20–12:23:41
    }

    def "显式范围：首尾部分桶覆盖秒数按实际查询范围计算"() {
        given:
        def from = Instant.parse("2026-09-24T04:05:30Z")   // 12:05:30
        def to = Instant.parse("2026-09-24T04:25:10Z")     // 12:25:10

        when:
        def buckets = resolver.resolve(new RangeSpec.Explicit(from, to, Granularity.MINUTE_10), SH)

        then:
        buckets[0].getStart() == Instant.parse("2026-09-24T04:00:00Z")
        buckets[0].isPartial()
        buckets[0].getCoveredSeconds() == 4 * 60 + 30          // 12:05:30–12:10:00
        buckets[-1].getStart() == Instant.parse("2026-09-24T04:20:00Z")
        buckets[-1].isPartial()
        buckets[-1].getCoveredSeconds() == 5 * 60 + 10         // 12:20:00–12:25:10
    }

    def "alignStart 按平台时区向下对齐"() {
        expect:
        resolver.alignStart(Instant.parse("2026-09-24T04:23:41Z"), Granularity.MINUTE_10, SH)
                == Instant.parse("2026-09-24T04:20:00Z")
        resolver.alignStart(Instant.parse("2026-09-24T04:23:41Z"), Granularity.HOUR_1, SH)
                == Instant.parse("2026-09-24T04:00:00Z")
        resolver.alignStart(Instant.parse("2026-09-24T04:23:41Z"), Granularity.DAY_1, SH)
                == Instant.parse("2026-09-23T16:00:00Z")
    }

    // ── 粒度反查 ─────────────────────────────────────────────

    @Unroll
    def "秒数 #seconds 反查为 #expected"() {
        expect:
        Granularity.fromSeconds(seconds) == expected

        where:
        seconds  | expected
        60       | Granularity.MINUTE_1
        300      | Granularity.MINUTE_5
        600      | Granularity.MINUTE_10
        1200     | Granularity.MINUTE_20
        3600     | Granularity.HOUR_1
        86400    | Granularity.DAY_1
    }

    @Unroll
    def "无法对齐的桶长 #seconds 反查为 null（不猜一个近似档位）"() {
        expect: "对不上档位就返回 null，让调用方回落到 range 的默认粒度"
        Granularity.fromSeconds(seconds) == null

        where:
        seconds << [0, 1, 59, 90, 450, 7200, 604800, -60]
    }
}
