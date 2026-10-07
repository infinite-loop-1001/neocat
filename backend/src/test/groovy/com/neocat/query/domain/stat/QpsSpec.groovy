package com.neocat.query.domain.stat

import com.neocat.analysis.domain.bucket.AggregatedRow
import com.neocat.analysis.domain.bucket.AggregationLevel
import com.neocat.analysis.domain.bucket.SeriesKey
import com.neocat.analysis.domain.bucket.SeriesKind
import spock.lang.Specification
import spock.lang.Unroll

import java.time.Instant

/**
 * G8 任务49（红）：统计项与 QPS 计算。
 * 对应 PRD 03 §3（桶内算法）、§4（QPS 与趋势显示）、§5（空数据）、
 * PRD 00 §5（CAT 口径 QPS）。
 */
class QpsSpec extends Specification {

    StatCalculator calc = new StatCalculator()

    def key = SeriesKey.of("order", SeriesKind.TRANSACTION, "URL", "/a")

    def row(long count, long failCount, long durationSum, long min, long max) {
        def r = new AggregatedRow(key, Instant.parse("2026-09-24T04:00:00Z"), AggregationLevel.MINUTE, 60)
        r.addCount(count, failCount, durationSum, min, max)
        // 填入可代表该桶的耗时样本，使分位计算有分布可用
        if (count > 0) {
            count.times { r.recordDuration(representative(min, max)) }
        }
        return r
    }

    def representative(long min, long max) {
        return max > 0 ? max : min
    }

    def rowsWithDurations(List<Long> durations) {
        def r = new AggregatedRow(key, Instant.parse("2026-09-24T04:00:00Z"), AggregationLevel.MINUTE, 60)
        long sum = 0
        long min = Long.MAX_VALUE
        long max = Long.MIN_VALUE
        durations.each { d ->
            sum += d
            min = Math.min(min, d)
            max = Math.max(max, d)
            r.recordDuration(d)
        }
        r.addCount(durations.size(), 0, sum, min, max)
        return r
    }

    // ── Hits / Failures ──────────────────────────────────────

    def "Hits 为所有事件次数求和"() {
        expect:
        calc.compute([row(100, 5, 5000, 10, 200), row(50, 1, 1000, 5, 100)], Stat.HITS, 60) == 150.0d
    }

    def "Failures 为失败次数求和"() {
        expect:
        calc.compute([row(100, 5, 5000, 10, 200), row(50, 1, 1000, 5, 100)], Stat.FAILURES, 60) == 6.0d
    }

    // ── QPS 三态 ─────────────────────────────────────────────

    def "当前未结束小时：QPS 分母为整点至当前时刻的实际秒数（不固定除 3600）"() {
        given: "13:00 起、当前 13:23:41，共 300 次"
        def coveredSeconds = 23 * 60 + 41        // 1421 秒

        when:
        def qps = calc.compute([row(300, 0, 3000, 1, 50)], Stat.QPS, coveredSeconds)

        then: "300 / 1421"
        qps == 300.0d / 1421

        and: "不等于错误地使用 3600 的结果"
        qps != 300.0d / 3600
    }

    def "完整历史小时：QPS 分母为 3600"() {
        when:
        def qps = calc.compute([row(3600, 0, 36000, 1, 50)], Stat.QPS, 3600)

        then:
        qps == 1.0d
    }

    def "自然日 / 自定义范围：QPS 分母为该报表实际覆盖秒数"() {
        when: "进行中的一天，覆盖 13 小时"
        def qps = calc.compute([row(46800, 0, 468000, 1, 50)], Stat.QPS, 13 * 3600)

        then:
        qps == 46800.0d / (13 * 3600)
    }

    def "多机器先合并总次数再除公共分母，不平均各机器 QPS"() {
        given: "机器 A 有 10 次、机器 B 有 90 次，统计范围 100 秒"
        def a = seriesRow("10.0.0.8", 10, 100)
        def b = seriesRow("10.0.0.9", 90, 900)

        when:
        def qps = calc.compute([a, b], Stat.QPS, 100)

        then: "正确值 = 100/100 = 1.0"
        qps == 1.0d

        and: "若错误地平均两台机器的 QPS 会得到 (0.1 + 0.9)/2 = 0.5"
        qps != 0.5d
    }

    def seriesRow(String instance, long count, long durationSum) {
        def k = SeriesKey.of("order", SeriesKind.TRANSACTION, "URL", "/a", instance)
        def r = new AggregatedRow(k, Instant.parse("2026-09-24T04:00:00Z"), AggregationLevel.MINUTE, 100)
        r.addCount(count, 0, durationSum, 1, 100)
        return r
    }

    // ── Avg / FailureRate / Min / Max ────────────────────────

    def "Avg Duration = 桶内总耗时 ÷ 桶内总次数"() {
        expect:
        calc.compute([row(100, 0, 5000, 10, 200)], Stat.AVG, 60) == 50.0d
    }

    def "Failure Rate = 桶内失败次数 ÷ 桶内总次数"() {
        expect:
        calc.compute([row(100, 25, 5000, 1, 1)], Stat.FAILURE_RATE, 60) == 0.25d
    }

    def "Min 取真实最小值，Max 取真实最大值"() {
        expect:
        calc.compute([row(10, 0, 100, 40, 90), row(10, 0, 100, 5, 200)], Stat.MIN, 60) == 5.0d
        calc.compute([row(10, 0, 100, 40, 90), row(10, 0, 100, 5, 200)], Stat.MAX, 60) == 200.0d
    }

    def "Min/Max 不能通过求和得到（跨桶合并取真实极值）"() {
        given:
        def merged = calc.merge([row(1, 0, 10, 30, 30), row(1, 0, 10, 70, 70)])

        expect:
        merged.getDurationMin() == 30
        merged.getDurationMax() == 70

    }

    // ── 分位 ─────────────────────────────────────────────────

    @Unroll
    def "分位基于合并后的原始分布：#percentile → 期望区间"() {
        given: "10 次 10ms，90 次 1000ms"
        def rows = [rowsWithDurations((1..10).collect { 10L }), rowsWithDurations((1..90).collect { 1000L })]

        when:
        def value = calc.compute(rows, stat, 60)

        then:
        value >= lowerBound

        where:
        stat       | percentile | lowerBound
        Stat.TP50  | 0.50d      | 1000.0d
        Stat.TP90  | 0.90d      | 1000.0d
        Stat.TP99  | 0.99d      | 1000.0d
        Stat.TP999 | 0.999d     | 0.0d
    }

    def "分位不平均子桶分位（错误做法会得到 505）"() {
        given:
        def rows = [rowsWithDurations((1..10).collect { 10L }), rowsWithDurations((1..90).collect { 1000L })]

        when:
        def p50 = calc.compute(rows, Stat.TP50, 60)

        then:
        p50 > 505.0d
    }

    def "同一时间桶只有一个值时各分位与该值相同"() {
        given:
        def rows = [rowsWithDurations([42L])]

        expect:
        calc.compute(rows, Stat.TP50, 60) == 42.0d
        calc.compute(rows, Stat.TP99, 60) == 42.0d
        calc.compute(rows, Stat.TP9999, 60) == 42.0d
    }

    def "分位值映射正确：tp9999 对应 0.9999"() {
        expect:
        Stat.TP50.percentileFraction() == 0.50d
        Stat.TP90.percentileFraction() == 0.90d
        Stat.TP99.percentileFraction() == 0.99d
        Stat.TP999.percentileFraction() == 0.999d
        Stat.TP9999.percentileFraction() == 0.9999d
        Stat.HITS.percentileFraction() == -1.0d
    }

    // ── 无数据 ───────────────────────────────────────────────

    def "无行时所有统计项返回 null（缺数不等于零）"() {
        expect:
        calc.compute([], Stat.HITS, 60) == null
        calc.compute([], Stat.QPS, 60) == null
        calc.compute([], Stat.AVG, 60) == null
        calc.compute([], Stat.FAILURE_RATE, 60) == null
        calc.compute([], Stat.TP99, 60) == null
    }

    def "确认无调用（count=0）时：次数类为 0，耗时与比例类为 null"() {
        given:
        def empty = row(0, 0, 0, 0, 0)

        expect: "次数类显示 0"
        calc.compute([empty], Stat.HITS, 60) == 0.0d
        calc.compute([empty], Stat.FAILURES, 60) == 0.0d
        calc.compute([empty], Stat.QPS, 60) == 0.0d

        and: "耗时类与比例类显示无值"
        calc.compute([empty], Stat.AVG, 60) == null
        calc.compute([empty], Stat.FAILURE_RATE, 60) == null
        calc.compute([empty], Stat.TP99, 60) == null
    }

    def "coveredSeconds 为 0 时 QPS 为无值而非除零"() {
        expect:
        calc.compute([row(10, 0, 100, 1, 1)], Stat.QPS, 0) == null
    }

    // ── 单位与适用性 ─────────────────────────────────────────

    def "统计项单位正确，供大盘公式校验使用"() {
        expect:
        Stat.HITS.unit() == StatUnit.COUNT
        Stat.FAILURES.unit() == StatUnit.COUNT
        Stat.QPS.unit() == StatUnit.RATE
        Stat.FAILURE_RATE.unit() == StatUnit.RATE
        Stat.AVG.unit() == StatUnit.DURATION
        Stat.TP99.unit() == StatUnit.DURATION
    }

    def "Event 只支持次数类与 QPS，不支持耗时与分位"() {
        expect:
        Stat.HITS.applicableToEvent()
        Stat.FAILURES.applicableToEvent()
        Stat.FAILURE_RATE.applicableToEvent()
        Stat.QPS.applicableToEvent()

        and:
        !Stat.AVG.applicableToEvent()
        !Stat.MIN.applicableToEvent()
        !Stat.MAX.applicableToEvent()
        !Stat.TP99.applicableToEvent()
    }

    def "异常类 Problem 只支持次数，不支持分位"() {
        expect:
        Stat.HITS.applicableToExceptionProblem()
        Stat.FAILURES.applicableToExceptionProblem()

        and:
        !Stat.TP99.applicableToExceptionProblem()
        !Stat.AVG.applicableToExceptionProblem()
    }
}
