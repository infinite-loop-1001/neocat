package com.neocat.analysis.domain.analyzer

import com.neocat.analysis.domain.bucket.SeriesKey
import com.neocat.analysis.domain.bucket.SeriesKind

import spock.lang.Specification

import java.time.Instant
import com.neocat.analysis.infra.store.InMemoryHourlyReportStore
import com.neocat.ingest.domain.tree.NodeKind
import com.neocat.ingest.domain.tree.RawNode

/**
 * G6 任务31（红）：Event 分析器。
 * 对应 PRD 03 §8：遵循同样的 Type → Name → 趋势交互；
 * 默认 Hits 总次数；可切换失败量、失败率、Event QPS；**不提供耗时与分位**。
 */
class EventAnalyzerSpec extends Specification {

    static final Instant T = Instant.parse("2026-09-24T04:23:41Z")

    EventAnalyzer analyzer
    InMemoryHourlyReportStore store

    def setup() {
        store = new InMemoryHourlyReportStore()
        analyzer = new EventAnalyzer(store)
    }

    def "分析器声明自己的域名为 event"() {
        expect:
        analyzer.domain() == "event"
    }

    def "Event 节点同时累加全机器行与实例行"() {
        when:
        analyzer.analyze(AnalysisFixtures.eventTree("order", "10.0.0.8", T.toEpochMilli(),
                "business", "order-created", "0"))

        then:
        store.seriesKeys().contains(SeriesKey.of("order", SeriesKind.EVENT, "business", "order-created", "all"))
        store.seriesKeys().contains(SeriesKey.of("order", SeriesKind.EVENT, "business", "order-created", "10.0.0.8"))
    }

    def "成功事件计入 count，不计入 failCount"() {
        when:
        analyzer.analyze(AnalysisFixtures.eventTree("order", "10.0.0.8", T.toEpochMilli(),
                "business", "order-created", "0"))

        then:
        def bucket = store.bucket(SeriesKey.of("order", SeriesKind.EVENT, "business", "order-created"), T)
        bucket.count() == 1
        bucket.failCount() == 0
    }

    def "非成功状态计为失败"() {
        when:
        analyzer.analyze(AnalysisFixtures.eventTree("order", "10.0.0.8", T.toEpochMilli(),
                "business", "order-created", "ERROR"))

        then:
        def bucket = store.bucket(SeriesKey.of("order", SeriesKind.EVENT, "business", "order-created"), T)
        bucket.count() == 1
        bucket.failCount() == 1
    }

    def "失败率按次数计算"() {
        when:
        3.times {
            analyzer.analyze(AnalysisFixtures.eventTree("order", "10.0.0.8", T.toEpochMilli(),
                    "business", "e", "0"))
        }
        analyzer.analyze(AnalysisFixtures.eventTree("order", "10.0.0.8", T.toEpochMilli(),
                "business", "e", "FAIL"))

        then:
        def bucket = store.bucket(SeriesKey.of("order", SeriesKind.EVENT, "business", "e"), T)
        bucket.count() == 4
        bucket.failCount() == 1
        bucket.failureRate() == 0.25d
    }

    def "Event QPS 由桶内次数与覆盖秒数换算"() {
        when:
        12.times {
            analyzer.analyze(AnalysisFixtures.eventTree("order", "10.0.0.8", T.toEpochMilli(),
                    "business", "e", "0"))
        }

        then:
        def bucket = store.bucket(SeriesKey.of("order", SeriesKind.EVENT, "business", "e"), T)
        bucket.qps(60) == 0.2d
        bucket.qps(30) == 0.4d
    }

    def "Event 不产出耗时统计：事件本身没有耗时概念"() {
        when:
        analyzer.analyze(AnalysisFixtures.eventTree("order", "10.0.0.8", T.toEpochMilli(),
                "business", "e", "0"))

        then: "分布为空，分位为无值；耗时和为 0"
        def bucket = store.bucket(SeriesKey.of("order", SeriesKind.EVENT, "business", "e"), T)
        bucket.durationSum() == 0
        bucket.distribution().count() == 0
        bucket.distribution().percentile(0.99d) == null
    }

    def "Transaction 节点不由 Event 分析器处理"() {
        when:
        analyzer.analyze(AnalysisFixtures.tree("order", "10.0.0.8", T.toEpochMilli(), "URL", "/a", "0", 10L))

        then:
        store.seriesKeys().isEmpty()
    }

    def "Metric 与 Heartbeat 节点不由 Event 分析器处理"() {
        when:
        analyzer.analyze(AnalysisFixtures.metricTree("order", "10.0.0.8", T.toEpochMilli(), "m", 1.0d))
        analyzer.analyze(AnalysisFixtures.heartbeatTree("order", "10.0.0.8", T.toEpochMilli()))

        then:
        store.seriesKeys().isEmpty()
    }

    def "桶归属使用节点事件时间"() {
        given:
        def tree = AnalysisFixtures.treeWithTimes("order", "10.0.0.8", T.plusSeconds(300).toEpochMilli(),
                [new RawNode("n-1", NodeKind.EVENT,
                        "business", "e", "0", T.toEpochMilli(), 0L, null, null, null, null, null, Map.of())])

        when:
        analyzer.analyze(tree)

        then:
        store.bucket(SeriesKey.of("order", SeriesKind.EVENT, "business", "e"), T).count() == 1
        store.bucket(SeriesKey.of("order", SeriesKind.EVENT, "business", "e"), T.plusSeconds(300)) == null
    }

    def "不同 Event Type 与 Name 独立成序列"() {
        when:
        analyzer.analyze(AnalysisFixtures.eventTree("order", "10.0.0.8", T.toEpochMilli(), "business", "a", "0"))
        analyzer.analyze(AnalysisFixtures.eventTree("order", "10.0.0.8", T.toEpochMilli(), "business", "b", "0"))
        analyzer.analyze(AnalysisFixtures.eventTree("order", "10.0.0.8", T.toEpochMilli(), "system", "a", "0"))

        then:
        store.bucket(SeriesKey.of("order", SeriesKind.EVENT, "business", "a"), T).count() == 1
        store.bucket(SeriesKey.of("order", SeriesKind.EVENT, "business", "b"), T).count() == 1
        store.bucket(SeriesKey.of("order", SeriesKind.EVENT, "system", "a"), T).count() == 1
    }
}
