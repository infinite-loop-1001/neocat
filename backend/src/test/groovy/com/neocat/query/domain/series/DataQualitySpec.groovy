package com.neocat.query.domain.series

import com.neocat.query.domain.stat.Stat

import spock.lang.Specification
import spock.lang.Unroll

/**
 * G8 任务51（红）：数据质量标记。
 * 对应 PRD 00 §6（统一数据质量语义表）、PRD 03 §5（空数据与部分数据）、
 * PRD 06 §6（缺数与零值）、PRD 04 §3（跨小时缺口）。
 */
class DataQualitySpec extends Specification {

    QualityResolver resolver = new QualityResolver()

    static QualityInput input(Map<String, Object> overrides = [:]) {
        def base = [seriesExists: true, count: 100L, dropped: false, mergedIntoOther: false,
                    partial: false, realtime: false, coveredSeconds: 60L]
        base.putAll(overrides)
        return new QualityInput(
                (boolean) base.seriesExists, (long) base.count, (boolean) base.dropped,
                (boolean) base.mergedIntoOther, (boolean) base.partial, (boolean) base.realtime,
                (long) base.coveredSeconds)
    }

    // ── 七种状态逐一 ─────────────────────────────────────────

    def "有数据 → OK"() {
        expect:
        resolver.resolve(input()) == Quality.OK
    }

    def "确认无调用（序列存在且次数为零）→ ZERO"() {
        expect:
        resolver.resolve(input(count: 0L)) == Quality.ZERO
    }

    def "采集丢弃 / 分析失败 / 序列不存在 → NO_DATA"() {
        expect: "序列不存在"
        resolver.resolve(input(seriesExists: false, count: 0L, coveredSeconds: 0L)) == Quality.NO_DATA
    }

    def "队列满丢弃记录 → DROPPED"() {
        expect:
        resolver.resolve(input(dropped: true, count: 0L)) == Quality.DROPPED
    }

    def "Metric 具体序列该小时并入 other → MERGED_OTHER"() {
        expect:
        resolver.resolve(input(seriesExists: false, count: 0L, mergedIntoOther: true)) == Quality.MERGED_OTHER
    }

    def "部分覆盖 → PARTIAL"() {
        expect:
        resolver.resolve(input(partial: true, coveredSeconds: 30L)) == Quality.PARTIAL
    }

    def "当前仍在写入的桶 → REALTIME"() {
        expect:
        resolver.resolve(input(realtime: true, coveredSeconds: 30L)) == Quality.REALTIME
    }

    // ── 判定优先级 ───────────────────────────────────────────

    def "丢弃优先于其他所有条件：即使桶内次数为 0 也报告 DROPPED 而非 ZERO"() {
        expect:
        resolver.resolve(input(dropped: true, count: 0L)) == Quality.DROPPED
        resolver.resolve(input(dropped: true, count: 50L)) == Quality.DROPPED
        resolver.resolve(input(dropped: true, realtime: true)) == Quality.DROPPED
    }

    def "并入 other 优先于序列不存在的 NO_DATA"() {
        expect: "两者条件同时命中时，给出更具体的 MERGED_OTHER"
        resolver.resolve(input(seriesExists: false, count: 0L, mergedIntoOther: true)) == Quality.MERGED_OTHER
    }

    def "序列不存在优先于确认无调用"() {
        expect: "没有序列时不能声称确认无调用"
        resolver.resolve(input(seriesExists: false, count: 0L)) == Quality.NO_DATA
        resolver.resolve(input(seriesExists: false, count: 0L)) != Quality.ZERO
    }

    def "实时桶优先于部分覆盖"() {
        expect:
        resolver.resolve(input(realtime: true, partial: true)) == Quality.REALTIME
    }

    def "实时桶即使次数为 0 也不显示为 ZERO"() {
        expect: "当前桶照常展示并标记实时，尚未结束不能断言确认无调用"
        resolver.resolve(input(realtime: true, count: 0L, coveredSeconds: 30L)) == Quality.REALTIME
    }

    def "部分覆盖优先于确认无调用"() {
        expect:
        resolver.resolve(input(partial: true, count: 0L, coveredSeconds: 20L)) == Quality.PARTIAL
    }

    // ── 缺数不等于零 ─────────────────────────────────────────

    @Unroll
    def "缺口状态不参与比较：#quality"() {
        expect:
        quality.gap()
        !quality.comparable()

        where:
        quality << [Quality.NO_DATA, Quality.DROPPED, Quality.MERGED_OTHER]
    }

    @Unroll
    def "可比较状态可参与告警窗口：#quality"() {
        expect:
        quality.comparable()
        !quality.gap()

        where:
        quality << [Quality.OK, Quality.ZERO, Quality.PARTIAL, Quality.REALTIME]
    }

    def "ZERO 是可比较的（确认无调用可以参与告警比较）"() {
        expect: "完整且确认无调用的次数类点可以是 0（PRD 06 §6）"
        Quality.ZERO.comparable()
        !Quality.ZERO.gap()
    }

    // ── 统计项层面的有值判定 ─────────────────────────────────

    def "ZERO 时次数类有值（显示 0）"() {
        expect:
        resolver.hasValue(Quality.ZERO, Stat.HITS)
        resolver.hasValue(Quality.ZERO, Stat.FAILURES)
        resolver.hasValue(Quality.ZERO, Stat.QPS)
    }

    def "ZERO 时耗时类与比例类无值（PRD 03 §5）"() {
        expect:
        !resolver.hasValue(Quality.ZERO, Stat.AVG)
        !resolver.hasValue(Quality.ZERO, Stat.MIN)
        !resolver.hasValue(Quality.ZERO, Stat.MAX)
        !resolver.hasValue(Quality.ZERO, Stat.TP99)
        !resolver.hasValue(Quality.ZERO, Stat.FAILURE_RATE)
    }

    @Unroll
    def "缺口状态下所有统计项都无值：#quality"() {
        expect:
        Stat.values().each { stat ->
            assert !resolver.hasValue(quality, stat)
        }

        where:
        quality << [Quality.NO_DATA, Quality.DROPPED, Quality.MERGED_OTHER]
    }

    def "OK 状态下所有统计项都有值"() {
        expect:
        Stat.values().each { stat ->
            assert resolver.hasValue(Quality.OK, stat)
        }
    }

    def "PARTIAL 与 REALTIME 状态下统计项照常有值"() {
        expect:
        Stat.values().each { stat ->
            assert resolver.hasValue(Quality.PARTIAL, stat)
            assert resolver.hasValue(Quality.REALTIME, stat)
        }
    }

    // ── 状态约束 ─────────────────────────────────────────────

    def "PARTIAL 的覆盖秒数必须小于桶长度（否则不是部分覆盖）"() {
        expect: "覆盖 60 秒的桶不算部分覆盖"
        resolver.resolve(input(partial: true, coveredSeconds: 60L)) != Quality.PARTIAL
    }

    def "序列存在且 count 大于 0 时不是 ZERO"() {
        expect:
        resolver.resolve(input(count: 1L)) == Quality.OK
    }

    def "七个质量状态完整覆盖设计文档"() {
        expect:
        Quality.values()*.name() as Set ==
                ["OK", "ZERO", "NO_DATA", "DROPPED", "MERGED_OTHER", "PARTIAL", "REALTIME"] as Set
    }
}
