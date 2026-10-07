package com.neocat.analysis.domain.metric

import com.neocat.analysis.domain.analyzer.AnalysisFixtures
import com.neocat.analysis.domain.analyzer.MetricAnalyzer
import com.neocat.analysis.domain.bucket.SeriesKey
import com.neocat.common.config.MetricConfig

import spock.lang.Specification

import java.time.Instant

/**
 * G6 任务37（红）：Metric 标签规范化与 Top1000 + other。
 * 对应 PRD 04 §2（规范化与排名）、§3（小时重排与跨小时缺口）、§9（验收）。
 */
class MetricRankSpec extends Specification {

    static final Instant H10 = Instant.parse("2026-09-24T02:00:00Z")   // 平台时区 10:00
    static final Instant H11 = Instant.parse("2026-09-24T03:00:00Z")   // 平台时区 11:00

    com.neocat.analysis.infra.store.InMemoryMetricHourRank rank
    com.neocat.analysis.infra.store.InMemoryHourlyReportStore store
    MetricAnalyzer analyzer

    def setup() {
        rank = new com.neocat.analysis.infra.store.InMemoryMetricHourRank()
        store = new com.neocat.analysis.infra.store.InMemoryHourlyReportStore()
        analyzer = new MetricAnalyzer(store, rank)
    }

    // ── 标签规范化 ───────────────────────────────────────────

    def "标签键按稳定顺序规范化：顺序不同但内容相同视为同一序列"() {
        expect:
        MetricLabels.canonicalize(["city": "上海", "channel": "app"])
                == MetricLabels.canonicalize(["channel": "app", "city": "上海"])
    }

    def "标签值不同视为不同序列"() {
        expect:
        MetricLabels.canonicalize(["city": "上海"]) != MetricLabels.canonicalize(["city": "北京"])
    }

    def "无标签与空标签都规范化为空串"() {
        expect:
        MetricLabels.canonicalize(null) == ""
        MetricLabels.canonicalize([:]) == ""
    }

    // ── Top1000 保留 ─────────────────────────────────────────

    def "上报次数在前 1000 的标签组合被保留为独立序列"() {
        given:
        MetricConfig.TOP_N = 3
        def small = new com.neocat.analysis.infra.store.InMemoryMetricHourRank()

        expect:
        small.record("order", "m", "a=1;", H10) == "a=1;"
        small.record("order", "m", "b=1;", H10) == "b=1;"
        small.record("order", "m", "c=1;", H10) == "c=1;"
    }

    def "第 1001 个及之后的组合并入 other"() {
        given:
        MetricConfig.TOP_N = 2
        def small = new com.neocat.analysis.infra.store.InMemoryMetricHourRank()

        expect:
        small.record("order", "m", "a=1;", H10) == "a=1;"
        small.record("order", "m", "b=1;", H10) == "b=1;"

        and: "第三个组合超出上限，归入 other"
        small.record("order", "m", "c=1;", H10) == SeriesKey.OTHER_LABELS
    }

    def "Top1000 限制不拒绝上报：超出后仍返回归属序列而非抛异常"() {
        given: "先占满名额"
        MetricConfig.TOP_N = 1
        def small = new com.neocat.analysis.infra.store.InMemoryMetricHourRank()
        small.record("order", "m", "a=1;", H10)

        when: "超出的组合并入 other"
        def owner = small.record("order", "m", "overflow=1;", H10)

        then: "不抛异常，而是给出明确的归属序列"
        noExceptionThrown()
        owner == SeriesKey.OTHER_LABELS
    }

    def "已被保留的序列继续上报仍归入自己，不会因后续序列出现而漂移"() {
        given:
        MetricConfig.TOP_N = 2
        def small = new com.neocat.analysis.infra.store.InMemoryMetricHourRank()
        small.record("order", "m", "a=1;", H10)
        small.record("order", "m", "b=1;", H10)
        small.record("order", "m", "c=1;", H10)         // 进 other

        expect: "a 仍在自己的序列"
        small.record("order", "m", "a=1;", H10) == "a=1;"
    }

    // ── 小时独立 ─────────────────────────────────────────────

    def "每个自然小时独立排名：新小时从空排名开始"() {
        given:
        MetricConfig.TOP_N = 1
        def small = new com.neocat.analysis.infra.store.InMemoryMetricHourRank()
        small.record("order", "m", "a=1;", H10)

        expect: "10 点 a 已占用唯一名额"
        small.record("order", "m", "b=1;", H10) == SeriesKey.OTHER_LABELS

        and: "11 点是新的空排名，b 可以独立保留"
        small.record("order", "m", "b=1;", H11) == "b=1;"
    }

    def "同一组合可以从独立序列变成 other，也可以反向变化"() {
        given:
        MetricConfig.TOP_N = 1
        def small = new com.neocat.analysis.infra.store.InMemoryMetricHourRank()

        expect: "10 点 a 独立、b 进 other"
        small.record("order", "m", "a=1;", H10) == "a=1;"
        small.record("order", "m", "b=1;", H10) == SeriesKey.OTHER_LABELS

        and: "11 点 b 独立、a 进 other"
        small.record("order", "m", "b=1;", H11) == "b=1;"
        small.record("order", "m", "a=1;", H11) == SeriesKey.OTHER_LABELS
    }

    def "不同服务与不同指标名各自独立排名"() {
        given:
        MetricConfig.TOP_N = 1
        def small = new com.neocat.analysis.infra.store.InMemoryMetricHourRank()

        expect:
        small.record("order", "m1", "a=1;", H10) == "a=1;"
        small.record("order", "m2", "a=1;", H10) == "a=1;"     // 不同指标名，不占同一名额
        small.record("pay", "m1", "a=1;", H10) == "a=1;"       // 不同服务，不占同一名额
        and: "同一 (服务,指标名) 下的第二个组合进 other"
        small.record("order", "m1", "b=1;", H10) == SeriesKey.OTHER_LABELS
    }

    // ── 固化与跨小时缺口 ─────────────────────────────────────

    def "固化后新增的标签组合按固化结果归属：未上前 1000 的进 other"() {
        given:
        MetricConfig.TOP_N = 1
        def small = new com.neocat.analysis.infra.store.InMemoryMetricHourRank()
        small.record("order", "m", "a=1;", H10)
        small.finalizeHour(H10)

        expect:
        small.finalized(H10)
        small.record("order", "m", "late=1;", H10) == SeriesKey.OTHER_LABELS
        and: "已保留的序列仍归自己"
        small.record("order", "m", "a=1;", H10) == "a=1;"
    }

    def "mergedIntoOther 用于表达跨小时缺口：该小时被并入 other 的组合为 true"() {
        given:
        MetricConfig.TOP_N = 1
        def small = new com.neocat.analysis.infra.store.InMemoryMetricHourRank()
        small.record("order", "m", "a=1;", H10)
        small.record("order", "m", "b=1;", H10)
        small.finalizeHour(H10)

        expect:
        small.mergedIntoOther("order", "m", "b=1;", H10) == true
        small.mergedIntoOther("order", "m", "a=1;", H10) == false
    }

    def "promotedLabels 返回该小时保留的独立序列集合"() {
        given:
        MetricConfig.TOP_N = 2
        def small = new com.neocat.analysis.infra.store.InMemoryMetricHourRank()
        small.record("order", "m", "a=1;", H10)
        small.record("order", "m", "b=1;", H10)
        small.record("order", "m", "c=1;", H10)

        expect:
        small.promotedLabels("order", "m", H10) == ["a=1;", "b=1;"] as Set
        !small.promotedLabels("order", "m", H10).contains("c=1;")
    }

    // ── 分析器写入 ───────────────────────────────────────────

    def "分析器把 Metric 数值写入对应序列"() {
        when:
        analyzer.analyze(AnalysisFixtures.metricTree("order", "10.0.0.8", H10.plusSeconds(600).toEpochMilli(),
                "order.amount", 128.5d, [city: "上海"]))

        then:
        def key = SeriesKey.metric("order", "order.amount", "city=上海;")
        store.bucket(key, H10.plusSeconds(600)).valueCount() == 1
        store.bucket(key, H10.plusSeconds(600)).valueSum() == 128.5d
    }

    def "超出 Top1000 的标签组合写入 other 序列"() {
        given:
        MetricConfig.TOP_N = 1
        def tinyRank = new com.neocat.analysis.infra.store.InMemoryMetricHourRank()
        def tinyStore = new com.neocat.analysis.infra.store.InMemoryHourlyReportStore()
        def tiny = new MetricAnalyzer(tinyStore, tinyRank)
        def t = H10.plusSeconds(60).toEpochMilli()

        when:
        tiny.analyze(AnalysisFixtures.metricTree("order", "10.0.0.8", t, "m", 1.0d, [a: "1"]))
        tiny.analyze(AnalysisFixtures.metricTree("order", "10.0.0.8", t, "m", 2.0d, [b: "1"]))

        then: "第一个独立、第二个进 other"
        tinyStore.bucket(SeriesKey.metric("order", "m", "a=1;"),
                Instant.ofEpochMilli(t).truncatedTo(java.time.temporal.ChronoUnit.MINUTES)).valueSum() == 1.0d
        tinyStore.bucket(SeriesKey.metric("order", "m", SeriesKey.OTHER_LABELS),
                Instant.ofEpochMilli(t).truncatedTo(java.time.temporal.ChronoUnit.MINUTES)).valueSum() == 2.0d
    }

    def "同一时间桶只有一个值时各分位与该值相同"() {
        when:
        analyzer.analyze(AnalysisFixtures.metricTree("order", "10.0.0.8", H10.plusSeconds(600).toEpochMilli(),
                "m", 42.0d, [a: "1"]))

        then:
        def bucket = store.bucket(SeriesKey.metric("order", "m", "a=1;"), H10.plusSeconds(600))
        bucket.valueDistribution().percentile(0.5d) == 42.0d
        bucket.valueDistribution().percentile(0.99d) == 42.0d
    }

    def "Metric 分析器不处理 Transaction/Event/Heartbeat 节点"() {
        when:
        analyzer.analyze(AnalysisFixtures.tree("order", "10.0.0.8", H10.toEpochMilli(), "URL", "/a", "0", 1L))
        analyzer.analyze(AnalysisFixtures.eventTree("order", "10.0.0.8", H10.toEpochMilli(), "business", "e", "0"))
        analyzer.analyze(AnalysisFixtures.heartbeatTree("order", "10.0.0.8", H10.toEpochMilli()))

        then:
        store.seriesKeys().isEmpty()
    }

    def "分析器声明自己的域名为 metric"() {
        expect:
        analyzer.domain() == "metric"
    }
}
