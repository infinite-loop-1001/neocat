package com.neocat.query.domain.series

import com.neocat.common.time.bucket.Bucket
import spock.lang.Specification
import spock.lang.Unroll

import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime

/**
 * G8 任务55（红）：环比对齐。
 * 对应 PRD 03 §6（1/7/30 天前同时段、整日偏移、桶序号对齐、月环比非上月）、
 * PRD 03 §10（Heartbeat 不做环比）。
 */
class MomSpec extends Specification {

    static final ZoneId SH = ZoneId.of("Asia/Shanghai")

    MomAligner aligner = new MomAligner()

    static List<Bucket> bucketsStartingAt(Instant start, int count, long bucketSeconds) {
        def list = []
        def cursor = start
        count.times {
            list << new Bucket(cursor, cursor.plusSeconds(bucketSeconds), false, bucketSeconds)
            cursor = cursor.plusSeconds(bucketSeconds)
        }
        return list
    }

    // ── 整日偏移 ─────────────────────────────────────────────

    def "1 天前环比：每个桶整体前移 24 小时"() {
        given: "平台时区 10:20 起共 3 个 10 分钟桶"
        def start = ZonedDateTime.of(2026, 9, 24, 10, 20, 0, 0, SH).toInstant()
        def current = bucketsStartingAt(start, 3, 600)

        when:
        def shifted = aligner.shift(current, MomKind.DAY, SH)

        then:
        shifted.size() == 3
        shifted[0].getStart() == start.minusSeconds(86400)
        shifted[1].getStart() == start.plusSeconds(600).minusSeconds(86400)
        shifted[2].getStart() == start.plusSeconds(1200).minusSeconds(86400)
    }

    def "7 天前环比：整体前移 7 天"() {
        given:
        def start = ZonedDateTime.of(2026, 9, 24, 10, 20, 0, 0, SH).toInstant()

        when:
        def shifted = aligner.shift(bucketsStartingAt(start, 2, 600), MomKind.WEEK, SH)

        then:
        shifted[0].getStart() == start.minusSeconds(7 * 86400)
    }

    def "30 天前环比：整体前移 30 天，而不是上月同期"() {
        given: "9 月 24 日"
        def start = ZonedDateTime.of(2026, 9, 24, 10, 20, 0, 0, SH).toInstant()

        when:
        def shifted = aligner.shift(bucketsStartingAt(start, 1, 600), MomKind.MONTH, SH)

        then: "前移 30 天 = 8 月 25 日，而非 8 月 24 日（上月的同一天）"
        shifted[0].getStart() == ZonedDateTime.of(2026, 8, 25, 10, 20, 0, 0, SH).toInstant()
        shifted[0].getStart() != ZonedDateTime.of(2026, 8, 24, 10, 20, 0, 0, SH).toInstant()
    }

    def "月环比不是上一个自然月：3 月 31 日往前 30 天是 3 月 1 日而非 2 月"() {
        given:
        def start = ZonedDateTime.of(2026, 3, 31, 10, 0, 0, 0, SH).toInstant()

        when:
        def shifted = aligner.shift(bucketsStartingAt(start, 1, 3600), MomKind.MONTH, SH)

        then:
        shifted[0].getStart() == ZonedDateTime.of(2026, 3, 1, 10, 0, 0, 0, SH).toInstant()
    }

    // ── 桶序号对齐 ───────────────────────────────────────────

    def "桶序号对齐：10:20 桶对齐到昨天 10:20，而非昨天 10:00"() {
        given:
        def start = ZonedDateTime.of(2026, 9, 24, 10, 20, 0, 0, SH).toInstant()
        def current = bucketsStartingAt(start, 1, 600)

        when:
        def shifted = aligner.shift(current, MomKind.DAY, SH)

        then:
        shifted[0].getStart() == ZonedDateTime.of(2026, 9, 23, 10, 20, 0, 0, SH).toInstant()
        shifted[0].getStart() != ZonedDateTime.of(2026, 9, 23, 10, 0, 0, 0, SH).toInstant()
    }

    def "对比桶保持与当前桶相同的时长与顺序"() {
        given:
        def start = ZonedDateTime.of(2026, 9, 24, 10, 0, 0, 0, SH).toInstant()
        def current = bucketsStartingAt(start, 5, 60)

        when:
        def shifted = aligner.shift(current, MomKind.DAY, SH)

        then:
        shifted.size() == current.size()
        shifted.every { it.totalSeconds() == 60 }
        shifted*.getStart() == shifted*.getStart().sort()
    }

    def "跨自然周偏移由整日偏移自动处理"() {
        given: "周六"
        def start = ZonedDateTime.of(2026, 9, 26, 10, 0, 0, 0, SH).toInstant()

        when:
        def shifted = aligner.shift(bucketsStartingAt(start, 1, 3600), MomKind.WEEK, SH)

        then: "7 天前为上周六，而非对齐到某一周的边界"
        shifted[0].getStart() == ZonedDateTime.of(2026, 9, 19, 10, 0, 0, 0, SH).toInstant()
    }

    def "时区正确：UTC 时区下的偏移结果与上海不同"() {
        given:
        def start = ZonedDateTime.of(2026, 9, 24, 2, 0, 0, 0, ZoneId.of("UTC")).toInstant()

        when:
        def shifted = aligner.shift(bucketsStartingAt(start, 1, 3600), MomKind.DAY, SH)

        then: "整日偏移在时长上与一天完全相等，时区不改变 24 小时这一事实"
        shifted[0].getStart() == start.minusSeconds(86400)
    }

    def "空桶序列返回空结果"() {
        expect:
        aligner.shift([], MomKind.DAY, SH).isEmpty()
    }

    // ── 支持范围 ─────────────────────────────────────────────

    def "Transaction / Event / Problem / Metric 支持环比"() {
        expect:
        aligner.supported("TRANSACTION")
        aligner.supported("EVENT")
        aligner.supported("PROBLEM")
        aligner.supported("METRIC")
    }

    def "Heartbeat 一期不做环比（PRD 03 §10）"() {
        expect:
        !aligner.supported("HEARTBEAT")
    }

    def "支持判定不区分大小写"() {
        expect:
        aligner.supported("transaction")
        aligner.supported("Metric")
    }

    @Unroll
    def "#kind 的整日偏移为 #days 天"() {
        expect:
        kind.daysOffset() == days

        where:
        kind          | days
        MomKind.DAY   | 1
        MomKind.WEEK  | 7
        MomKind.MONTH | 30
    }

    def "环比三种类型完整覆盖设计文档"() {
        expect:
        MomKind.values()*.name() as Set == ["DAY", "WEEK", "MONTH"] as Set
    }
}
