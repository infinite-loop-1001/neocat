package com.neocat.common.time.range

import spock.lang.Specification
import spock.lang.Unroll

import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth

/**
 * `range` 参数解析（技术方案 03-api-contract.md §4.1）。
 *
 * <p>重点是固定周期写法：窗口导航（前后翻页 / h w m 刻度）依赖它。
 * 若这条链路被改坏，翻页会静默回到「最近 1 小时」——页面看起来正常但数据是错的。
 */
class RangeParamsSpec extends Specification {

    static final Instant NOW = Instant.parse("2026-10-01T11:00:00Z")

    // ── 固定周期：窗口导航的载体 ─────────────────────────────

    def "HOUR:<epoch> 解析为整点小时"() {
        given:
        def start = Instant.parse("2026-09-30T16:00:00Z").toEpochMilli()

        when:
        def spec = RangeParams.parse("HOUR:${start}", NOW)

        then:
        spec instanceof Hour
        (spec as Hour).getHourStart() == Instant.ofEpochMilli(start)
    }

    def "WEEK:<yyyy-MM-dd> 解析为自然周（取该日期所在周）"() {
        when:
        def spec = RangeParams.parse("WEEK:2026-09-24", NOW)

        then:
        spec instanceof Week
        (spec as Week).getAnyDateInWeek() == LocalDate.of(2026, 9, 24)
    }

    def "MONTH:<yyyy-MM> 解析为自然月"() {
        when:
        def spec = RangeParams.parse("MONTH:2026-09", NOW)

        then:
        spec instanceof Month
        (spec as Month).getMonth() == YearMonth.of(2026, 9)
    }

    def "DAY:<yyyy-MM-dd> 解析为自然日"() {
        when:
        def spec = RangeParams.parse("DAY:2026-09-24", NOW)

        then:
        spec instanceof Day
        (spec as Day).getDate() == LocalDate.of(2026, 9, 24)
    }

    // ── 快捷范围 ────────────────────────────────────────────

    @Unroll
    def "#raw 解析为快捷范围 #expected"() {
        when:
        def spec = RangeParams.parse(raw, NOW)

        then:
        spec instanceof QuickRange
        (spec as QuickRange).getQuick() == expected
        (spec as QuickRange).getNow() == NOW

        where:
        raw            | expected
        "RECENT_1H"    | RangeQuick.RECENT_1H
        "RECENT_3H"    | RangeQuick.RECENT_3H
        "RECENT_6H"    | RangeQuick.RECENT_6H
        "RECENT_12H"   | RangeQuick.RECENT_12H
        "RECENT_24H"   | RangeQuick.RECENT_24H
        "TODAY"        | RangeQuick.TODAY
        "THIS_WEEK"    | RangeQuick.THIS_WEEK
    }

    def "小写与前后空白同样被接受"() {
        expect:
        (RangeParams.parse("  recent_24h ", NOW) as QuickRange).getQuick() == RangeQuick.RECENT_24H
    }

    // ── 容错：坏输入不能把报表页打挂 ────────────────────────

    @Unroll
    def "无法识别的 range「#raw」回退到最近 1 小时"() {
        when:
        def spec = RangeParams.parse(raw, NOW)

        then:
        noExceptionThrown()
        spec instanceof QuickRange
        (spec as QuickRange).getQuick() == RangeQuick.RECENT_1H

        where:
        raw << [
                null,
                "",
                "   ",
                "NONSENSE",
                "HOUR:not-a-number",
                "DAY:2026-13-99",
                "WEEK:",
                "MONTH:2026-99",
                ":123",
        ]
    }
}
