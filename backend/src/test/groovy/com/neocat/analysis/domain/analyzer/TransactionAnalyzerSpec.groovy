package com.neocat.analysis.domain.analyzer

import com.neocat.analysis.domain.bucket.MinuteBucket
import com.neocat.analysis.domain.bucket.SeriesKey
import com.neocat.analysis.domain.bucket.SeriesKind

import spock.lang.Specification
import spock.lang.Unroll

import java.time.Instant

/**
 * G6 任务31（红）：Transaction 与 Event 分析器。
 * 对应 PRD 03 §7（Transaction）与 §8（Event）、§3（桶内聚合）。
 */
class TransactionAnalyzerSpec extends Specification {

    static final Instant T = Instant.parse("2026-09-24T04:23:41Z")

    TransactionAnalyzer analyzer
    com.neocat.analysis.infra.store.InMemoryHourlyReportStore store

    def setup() {
        store = new com.neocat.analysis.infra.store.InMemoryHourlyReportStore()
        analyzer = new TransactionAnalyzer(store)
    }

    // ── 序列维度 ─────────────────────────────────────────────

    def "Transaction 节点同时累加全机器行与实例行"() {
        when:
        analyzer.analyze(AnalysisFixtures.tree("order", "10.0.0.8", T.toEpochMilli(), "URL", "POST /orders", "0", 12L))

        then:
        store.seriesKeys().contains(SeriesKey.of("order", SeriesKind.TRANSACTION, "URL", "POST /orders", "all"))
        store.seriesKeys().contains(SeriesKey.of("order", SeriesKind.TRANSACTION, "URL", "POST /orders", "10.0.0.8"))
    }

    def "分类与名称共同构成序列身份（不同 Name 不混淆）"() {
        when:
        analyzer.analyze(AnalysisFixtures.tree("order", "10.0.0.8", T.toEpochMilli(), "URL", "POST /orders", "0", 12L))
        analyzer.analyze(AnalysisFixtures.tree("order", "10.0.0.8", T.toEpochMilli(), "URL", "POST /pay", "0", 30L))

        then:
        store.bucket(SeriesKey.of("order", SeriesKind.TRANSACTION, "URL", "POST /orders"), T).count() == 1
        store.bucket(SeriesKey.of("order", SeriesKind.TRANSACTION, "URL", "POST /pay"), T).count() == 1
    }

    def "不同服务不混淆"() {
        when:
        analyzer.analyze(AnalysisFixtures.tree("order", "10.0.0.8", T.toEpochMilli(), "URL", "/a", "0", 1L))
        analyzer.analyze(AnalysisFixtures.tree("pay", "10.0.0.9", T.toEpochMilli(), "URL", "/a", "0", 1L))

        then:
        store.bucket(SeriesKey.of("order", SeriesKind.TRANSACTION, "URL", "/a"), T).count() == 1
        store.bucket(SeriesKey.of("pay", SeriesKind.TRANSACTION, "URL", "/a"), T).count() == 1
    }

    // ── 计数与失败 ───────────────────────────────────────────

    def "成功调用计入 count，不计入 failCount"() {
        when:
        analyzer.analyze(AnalysisFixtures.tree("order", "10.0.0.8", T.toEpochMilli(), "URL", "/a", "0", 10L))

        then:
        def bucket = store.bucket(SeriesKey.of("order", SeriesKind.TRANSACTION, "URL", "/a"), T)
        bucket.count() == 1
        bucket.failCount() == 0
    }

    @Unroll
    def "状态 '#status' 计为失败"() {
        when:
        analyzer.analyze(AnalysisFixtures.tree("order", "10.0.0.8", T.toEpochMilli(), "URL", "/a", status, 10L))

        then:
        def bucket = store.bucket(SeriesKey.of("order", SeriesKind.TRANSACTION, "URL", "/a"), T)
        bucket.count() == 1
        bucket.failCount() == 1

        where:
        status << ["ERROR", "1", "FAIL", "-1"]
    }

    def "多次调用累加到同一分钟桶"() {
        when:
        3.times {
            analyzer.analyze(AnalysisFixtures.tree("order", "10.0.0.8", T.toEpochMilli(), "URL", "/a", "0", 10L))
        }

        then:
        store.bucket(SeriesKey.of("order", SeriesKind.TRANSACTION, "URL", "/a"), T).count() == 3
    }

    // ── 耗时统计 ─────────────────────────────────────────────

    def "耗时统计：sum / min / max / avg"() {
        when:
        analyzer.analyze(AnalysisFixtures.tree("order", "10.0.0.8", T.toEpochMilli(), "URL", "/a", "0", 10L))
        analyzer.analyze(AnalysisFixtures.tree("order", "10.0.0.8", T.toEpochMilli(), "URL", "/a", "0", 30L))
        analyzer.analyze(AnalysisFixtures.tree("order", "10.0.0.8", T.toEpochMilli(), "URL", "/a", "0", 20L))

        then:
        def bucket = store.bucket(SeriesKey.of("order", SeriesKind.TRANSACTION, "URL", "/a"), T)
        bucket.durationSum() == 60
        bucket.durationMin() == 10
        bucket.durationMax() == 30
        bucket.averageDuration() == 20.0d
    }

    def "失败调用的耗时同样进入耗时统计"() {
        when:
        analyzer.analyze(AnalysisFixtures.tree("order", "10.0.0.8", T.toEpochMilli(), "URL", "/a", "ERROR", 100L))

        then:
        def bucket = store.bucket(SeriesKey.of("order", SeriesKind.TRANSACTION, "URL", "/a"), T)
        bucket.durationSum() == 100
        bucket.averageDuration() == 100.0d
    }

    def "无调用时平均耗时与失败率为无值而非 0（PRD 03 §5）"() {
        given: "一个确认无调用的桶（存在但因未采集到调用而计数为 0）"
        def bucket = new MinuteBucket()

        expect: "次数类为 0"
        bucket.count() == 0
        bucket.failCount() == 0

        and: "耗时类与比例类为无值，而不是 0"
        bucket.averageDuration() == null
        bucket.failureRate() == null
        bucket.distribution().percentile(0.99d) == null
    }

    def "失败率 = 失败次数 / 总次数"() {
        when:
        analyzer.analyze(AnalysisFixtures.tree("order", "10.0.0.8", T.toEpochMilli(), "URL", "/a", "0", 1L))
        analyzer.analyze(AnalysisFixtures.tree("order", "10.0.0.8", T.toEpochMilli(), "URL", "/a", "0", 1L))
        analyzer.analyze(AnalysisFixtures.tree("order", "10.0.0.8", T.toEpochMilli(), "URL", "/a", "0", 1L))
        analyzer.analyze(AnalysisFixtures.tree("order", "10.0.0.8", T.toEpochMilli(), "URL", "/a", "ERROR", 1L))

        then:
        def bucket = store.bucket(SeriesKey.of("order", SeriesKind.TRANSACTION, "URL", "/a"), T)
        bucket.count() == 4
        bucket.failCount() == 1
        bucket.failureRate() == 0.25d
    }

    // ── 时间桶归属 ───────────────────────────────────────────

    def "桶归属使用节点事件时间，而非树时间或接收时间"() {
        given: "节点事件时间在 04:23，树时间故意设为较早"
        def tree = AnalysisFixtures.treeWithTimes(
                "order", "10.0.0.8", T.plusSeconds(300).toEpochMilli(),
                [AnalysisFixtures.node("n-1", "URL", "/a", "0", 10L, T.toEpochMilli())])

        when:
        analyzer.analyze(tree)

        then: "落在 04:23 桶，而不是 04:28"
        store.bucket(SeriesKey.of("order", SeriesKind.TRANSACTION, "URL", "/a"), T).count() == 1
        store.bucket(SeriesKey.of("order", SeriesKind.TRANSACTION, "URL", "/a"), T.plusSeconds(300)) == null
    }

    def "同一棵树内不同节点按各自事件时间落入不同分钟桶"() {
        given:
        def tree = AnalysisFixtures.treeWithTimes("order", "10.0.0.8", T.toEpochMilli(), [
                AnalysisFixtures.node("n-1", "URL", "/a", "0", 10L, T.toEpochMilli()),
                AnalysisFixtures.node("n-2", "URL", "/a", "0", 10L, T.plusSeconds(60).toEpochMilli())
        ])

        when:
        analyzer.analyze(tree)

        then:
        store.bucket(SeriesKey.of("order", SeriesKind.TRANSACTION, "URL", "/a"), T).count() == 1
        store.bucket(SeriesKey.of("order", SeriesKind.TRANSACTION, "URL", "/a"), T.plusSeconds(60)).count() == 1
    }

    // ── 非 Transaction 节点 ──────────────────────────────────

    def "Event 节点不由 Transaction 分析器处理"() {
        when:
        analyzer.analyze(AnalysisFixtures.eventTree("order", "10.0.0.8", T.toEpochMilli(), "business", "order-created", "0"))

        then:
        store.seriesKeys().isEmpty()
    }

    def "Metric 与 Heartbeat 节点不由 Transaction 分析器处理"() {
        when:
        analyzer.analyze(AnalysisFixtures.metricTree("order", "10.0.0.8", T.toEpochMilli(), "order.amount", 1.5d))
        analyzer.analyze(AnalysisFixtures.heartbeatTree("order", "10.0.0.8", T.toEpochMilli()))

        then:
        store.seriesKeys().isEmpty()
    }

    def "分析器声明自己的域名为 transaction"() {
        expect:
        analyzer.domain() == "transaction"
    }
}
