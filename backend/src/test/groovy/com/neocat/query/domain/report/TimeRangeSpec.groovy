package com.neocat.query.domain.report

import com.neocat.common.time.bucket.DefaultTimeBucketResolver
import com.neocat.common.time.bucket.Granularity
import com.neocat.common.time.range.RangeQuick
import com.neocat.common.time.range.RangeSpec
import spock.lang.Specification
import spock.lang.Unroll

import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId

/**
 * G8 任务47（红）：查询时间范围解析。
 * 对应 PRD 03 §2.1（固定周期）、§2.2（快捷范围）、§2.3（左闭右开）、
 * 技术方案 03 §4.2（bucket 参数与默认粒度）。
 */
class TimeRangeSpec extends Specification {

    static final ZoneId SH = ZoneId.of("Asia/Shanghai")

    RangeResolver resolver = new RangeResolver(new DefaultTimeBucketResolver())

    def "小时范围解析出 60 个 1 分钟桶"() {
        given:
        def hour = Instant.parse("2026-09-24T02:00:00Z")

        when:
        def resolved = resolver.resolve(new RangeSpec.Hour(hour), SH)

        then:
        resolved.getFrom() == hour
        resolved.getTo() == Instant.parse("2026-09-24T03:00:00Z")
        resolved.pointCount() == 60
        resolved.bucketSeconds() == 60
    }

    def "自然日范围解析出 144 个 10 分钟桶"() {
        when:
        def resolved = resolver.resolve(new RangeSpec.Day(LocalDate.of(2026, 9, 24)), SH)

        then:
        resolved.pointCount() == 144
        resolved.bucketSeconds() == 600
    }

    def "自然周范围解析出 168 个 1 小时桶"() {
        when:
        def resolved = resolver.resolve(new RangeSpec.Week(LocalDate.of(2026, 9, 24)), SH)

        then:
        resolved.pointCount() == 168
        resolved.bucketSeconds() == 3600
    }

    def "自然月范围解析出当月天数的日桶"() {
        when:
        def resolved = resolver.resolve(new RangeSpec.Month(YearMonth.of(2026, 9)), SH)

        then:
        resolved.pointCount() == 30
        resolved.bucketSeconds() == 86400
    }

    @Unroll
    def "最近 #hours 小时使用 #granularity 粒度"() {
        given:
        def now = Instant.parse("2026-09-24T04:23:41Z")
        def quick = switch (hours) {
            case 1 -> RangeQuick.RECENT_1H
            case 3 -> RangeQuick.RECENT_3H
            case 6 -> RangeQuick.RECENT_6H
            case 12 -> RangeQuick.RECENT_12H
            default -> RangeQuick.RECENT_24H
        }

        when:
        def resolved = resolver.resolve(new RangeSpec.QuickRange(quick, now), SH)

        then:
        resolved.bucketSeconds() == granularity.seconds()

        where:
        hours | granularity
        1     | Granularity.MINUTE_1
        3     | Granularity.MINUTE_5
        6     | Granularity.MINUTE_10
        12    | Granularity.MINUTE_20
        24    | Granularity.HOUR_1
    }

    def "今天使用 10 分钟粒度"() {
        given:
        def now = Instant.parse("2026-09-24T04:23:41Z")

        when:
        def resolved = resolver.resolve(new RangeSpec.QuickRange(RangeQuick.TODAY, now), SH)

        then:
        resolved.bucketSeconds() == 600
    }

    def "本周使用 1 小时粒度"() {
        given:
        def now = Instant.parse("2026-09-24T04:23:41Z")

        when:
        def resolved = resolver.resolve(new RangeSpec.QuickRange(RangeQuick.THIS_WEEK, now), SH)

        then:
        resolved.bucketSeconds() == 3600
    }

    def "最近12小时为 20 分钟/点（V2 修订项）"() {
        given:
        def now = Instant.parse("2026-09-24T04:23:41Z")

        when:
        def resolved = resolver.resolve(new RangeSpec.QuickRange(RangeQuick.RECENT_12H, now), SH)

        then:
        resolved.bucketSeconds() == 20 * 60

        and: "不是旧的 30 分钟或 1 小时"
        resolved.bucketSeconds() != 30 * 60
        resolved.bucketSeconds() != 3600
    }

    def "周趋势为 1 小时/点（V2 修订项，而非 CAT 的 1 天/点）"() {
        when:
        def resolved = resolver.resolve(new RangeSpec.Week(LocalDate.of(2026, 9, 24)), SH)

        then:
        resolved.bucketSeconds() == 3600
        resolved.bucketSeconds() != 86400
    }

    def "滚动范围首尾桶保留并标记部分覆盖"() {
        given:
        def now = Instant.parse("2026-09-24T04:23:41Z")

        when:
        def resolved = resolver.resolve(new RangeSpec.QuickRange(RangeQuick.RECENT_1H, now), SH)

        then:
        resolved.getBuckets()[0].isPartial()
        resolved.getBuckets()[-1].isPartial()
        resolved.getBuckets()[0].getCoveredSeconds() < 60
    }

    def "固定周期的桶全部为完整桶"() {
        when:
        def resolved = resolver.resolve(new RangeSpec.Hour(Instant.parse("2026-09-24T02:00:00Z")), SH)

        then:
        resolved.getBuckets().every { !it.isPartial() }
        resolved.getBuckets().every { it.getCoveredSeconds() == 60 }
    }

    @Unroll
    def "按范围长度推导默认粒度：#length → #expected"() {
        expect:
        resolver.inferredGranularity(Duration.ofSeconds(seconds)) == expected

        where:
        length             | seconds   | expected
        "1 小时"            | 3600      | Granularity.MINUTE_1
        "2 小时"            | 7200      | Granularity.MINUTE_5
        "5 小时"            | 18000     | Granularity.MINUTE_10
        "11 小时"           | 39600     | Granularity.MINUTE_20
        "20 小时"           | 72000     | Granularity.HOUR_1
        "3 天"              | 259200    | Granularity.DAY_1
    }

    def "空范围返回空结果而不报错"() {
        when:
        def resolved = resolver.resolve(
                new RangeSpec.Explicit(Instant.parse("2026-09-24T04:00:00Z"),
                        Instant.parse("2026-09-24T04:00:00Z"), Granularity.MINUTE_1), SH)

        then:
        noExceptionThrown()
        resolved.pointCount() == 0
    }

    def "左闭右开：点表示 [start, end)"() {
        given:
        def hour = Instant.parse("2026-09-24T02:00:00Z")

        when:
        def bucket = resolver.resolve(new RangeSpec.Hour(hour), SH).getBuckets()[0]

        then:
        bucket.contains(hour)
        !bucket.contains(bucket.getEnd())
        bucket.getStart().isBefore(bucket.getEnd())
    }
}
