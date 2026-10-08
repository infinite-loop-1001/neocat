package com.neocat.analysis.domain.analyzer

import com.neocat.analysis.domain.bucket.SeriesKey

import spock.lang.Specification
import spock.lang.Unroll

import java.time.Instant
import com.neocat.analysis.infra.store.InMemoryHourlyReportStore

/**
 * G6 任务33（红）：Problem 五类派生。
 * 对应 PRD 03 §9：异常按异常名聚合、慢类按 Transaction Name 聚合、
 * 同一次调用可同时属异常与慢类、阈值只影响后续、异常无分位。
 */
class ProblemAnalyzerSpec extends Specification {

    static final Instant T = Instant.parse("2026-09-24T04:23:41Z")

    InMemoryHourlyReportStore store
    ProblemAnalyzer analyzer

    def setup() {
        store = new InMemoryHourlyReportStore()
        // 平台默认阈值：URL 1000 / SQL 100 / 调用 1000 / 缓存 50
        analyzer = new ProblemAnalyzer(store, 1000, 100, 1000, 50)
    }

    def problemKey(String category, String name) {
        SeriesKey.problem("order", category, name)
    }

    def "分析器声明自己的域名为 problem"() {
        expect:
        analyzer.domain() == "problem"
    }

    // ── 异常类 ───────────────────────────────────────────────

    def "非成功的 Transaction 派生 EXCEPTION，聚合键为异常名"() {
        when:
        analyzer.analyze(AnalysisFixtures.exceptionTree("order", "10.0.0.8", T.toEpochMilli(),
                "URL", "POST /orders", "java.lang.NullPointerException", "boom", 5L))

        then:
        store.bucket(problemKey("EXCEPTION", "java.lang.NullPointerException"), T).count() == 1
    }

    def "不同异常名各自成序列"() {
        when:
        analyzer.analyze(AnalysisFixtures.exceptionTree("order", "10.0.0.8", T.toEpochMilli(),
                "URL", "POST /orders", "java.lang.NullPointerException", "a", 5L))
        analyzer.analyze(AnalysisFixtures.exceptionTree("order", "10.0.0.8", T.toEpochMilli(),
                "URL", "POST /orders", "java.lang.IllegalStateException", "b", 5L))

        then:
        store.bucket(problemKey("EXCEPTION", "java.lang.NullPointerException"), T).count() == 1
        store.bucket(problemKey("EXCEPTION", "java.lang.IllegalStateException"), T).count() == 1
    }

    def "同一异常名的多次调用累加到同一序列（聚合键是异常名而非消息）"() {
        when:
        3.times { i ->
            analyzer.analyze(AnalysisFixtures.exceptionTree("order", "10.0.0.8", T.toEpochMilli(),
                    "URL", "POST /orders", "java.lang.NullPointerException", "message-$i", 5L + i))
        }

        then: "三条消息不同但异常名相同，聚合为一个序列"
        store.bucket(problemKey("EXCEPTION", "java.lang.NullPointerException"), T).count() == 3
    }

    def "非成功的 Event 同样派生 EXCEPTION，无异常名时按节点名聚合"() {
        when:
        analyzer.analyze(AnalysisFixtures.eventTree("order", "10.0.0.8", T.toEpochMilli(),
                "business", "order-created", "ERROR"))

        then: "Event 无 exceptionName，退化聚合键为节点名"
        def key = store.seriesKeys().find { it.getProblemCategory() == "EXCEPTION" }
        key != null
        key.getName() == "order-created"
        store.bucket(key, T).count() == 1
    }

    @Unroll
    def "慢类：#category 类型耗时 #durationMs ms 相对阈值 #thresholdMs"() {
        given:
        def a = new ProblemAnalyzer(store, urlMs, sqlMs, callMs, cacheMs)

        when:
        a.analyze(AnalysisFixtures.tree("order", "10.0.0.8", T.toEpochMilli(), category, name, "0", durationMs))

        then:
        if (expectedSlow) {
            assert store.bucket(problemKey(expectedCategory, name), T)?.count() == 1
        } else {
            assert store.bucket(problemKey(expectedCategory, name), T) == null
        }

        where:
        category | name      | durationMs | urlMs | sqlMs | callMs | cacheMs | thresholdMs | expectedSlow | expectedCategory
        "URL"    | "/orders" | 1001       | 1000  | 100   | 1000   | 50      | 1000        | true         | "SLOW_URL"
        "URL"    | "/orders" | 1000       | 1000  | 100   | 1000   | 50      | 1000        | false        | "SLOW_URL"
        "SQL"    | "select1" | 101        | 1000  | 100   | 1000   | 50      | 100         | true         | "SLOW_SQL"
        "SQL"    | "select1" | 100        | 1000  | 100   | 1000   | 50      | 100         | false        | "SLOW_SQL"
        "CALL"   | "pay"     | 1001       | 1000  | 100   | 1000   | 50      | 1000        | true         | "SLOW_CALL"
        "CALL"   | "pay"     | 1000       | 1000  | 100   | 1000   | 50      | 1000        | false        | "SLOW_CALL"
        "CACHE"  | "getUser" | 51         | 1000  | 100   | 1000   | 50      | 50          | true         | "SLOW_CACHE"
        "CACHE"  | "getUser" | 50         | 1000  | 100   | 1000   | 50      | 50          | false        | "SLOW_CACHE"
    }

    def "阈值比较为严格大于：等于阈值不算慢"() {
        when:
        analyzer.analyze(AnalysisFixtures.tree("order", "10.0.0.8", T.toEpochMilli(), "SQL", "s", "0", 100L))

        then:
        store.bucket(problemKey("SLOW_SQL", "s"), T) == null
    }

    def "非 URL/SQL/CALL/CACHE 类型不派生慢类"() {
        when:
        analyzer.analyze(AnalysisFixtures.tree("order", "10.0.0.8", T.toEpochMilli(),
                "OTHER", "x", "0", 999_999L))

        then:
        store.seriesKeys().isEmpty()
    }

    def "Event 类型不派生慢类（Event 无耗时语义）"() {
        when:
        analyzer.analyze(AnalysisFixtures.eventTree("order", "10.0.0.8", T.toEpochMilli(),
                "URL", "/orders", "0"))

        then:
        store.seriesKeys().isEmpty()
    }

    // ── 可重叠 ───────────────────────────────────────────────

    def "同一次调用可同时属于异常与慢类"() {
        when: "一个失败的慢 SQL 调用"
        analyzer.analyze(AnalysisFixtures.exceptionTree("order", "10.0.0.8", T.toEpochMilli(),
                "SQL", "select_order", "java.sql.SQLTimeoutException", "timeout", 5000L))

        then: "两个序列各自产生一条"
        store.bucket(problemKey("EXCEPTION", "java.sql.SQLTimeoutException"), T).count() == 1
        store.bucket(problemKey("SLOW_SQL", "select_order"), T).count() == 1
    }

    def "可重叠时慢类保留耗时统计，异常不保留分位"() {
        when:
        analyzer.analyze(AnalysisFixtures.exceptionTree("order", "10.0.0.8", T.toEpochMilli(),
                "SQL", "select_order", "java.sql.SQLTimeoutException", "timeout", 5000L))

        then: "慢类有耗时与分位"
        def slow = store.bucket(problemKey("SLOW_SQL", "select_order"), T)
        slow.durationSum() == 5000
        slow.distribution().percentile(0.99d) != null

        and: "异常条目只计数，不承载耗时分布"
        def exception = store.bucket(problemKey("EXCEPTION", "java.sql.SQLTimeoutException"), T)
        exception.count() == 1
        exception.distribution().count() == 0
        exception.distribution().percentile(0.99d) == null
    }

    // ── 边界 ─────────────────────────────────────────────────

    def "成功的快调用不产生任何 Problem"() {
        when:
        analyzer.analyze(AnalysisFixtures.tree("order", "10.0.0.8", T.toEpochMilli(), "URL", "/a", "0", 10L))

        then:
        store.seriesKeys().isEmpty()
    }

    def "失败的快调用只产生 EXCEPTION，不产生慢类"() {
        when:
        analyzer.analyze(AnalysisFixtures.exceptionTree("order", "10.0.0.8", T.toEpochMilli(),
                "URL", "/a", "java.lang.RuntimeException", "x", 10L))

        then:
        store.seriesKeys().size() == 1
        store.bucket(problemKey("EXCEPTION", "java.lang.RuntimeException"), T).count() == 1
    }

    def "非成功状态但缺少异常名时退化为按名称聚合，不丢失记录"() {
        when:
        analyzer.analyze(AnalysisFixtures.tree("order", "10.0.0.8", T.toEpochMilli(), "URL", "/orders", "ERROR", 10L))

        then:
        store.seriesKeys().size() == 1
        def key = store.seriesKeys().find { it.getProblemCategory() == "EXCEPTION" }
        key != null
        store.bucket(key, T).count() == 1
    }

    def "慢类聚合键为 Transaction Name，而非实例"() {
        when:
        analyzer.analyze(AnalysisFixtures.tree("order", "10.0.0.8", T.toEpochMilli(), "SQL", "select_order", "0", 200L))
        analyzer.analyze(AnalysisFixtures.tree("order", "10.0.0.9", T.toEpochMilli(), "SQL", "select_order", "0", 300L))

        then: "同一 Name 跨实例聚合为一个 Problem 序列"
        def bucket = store.bucket(problemKey("SLOW_SQL", "select_order"), T)
        bucket.count() == 2
        bucket.durationSum() == 500
    }

    def "不同服务各自独立派生 Problem"() {
        when:
        analyzer.analyze(AnalysisFixtures.tree("order", "10.0.0.8", T.toEpochMilli(), "SQL", "s", "0", 200L))
        analyzer.analyze(AnalysisFixtures.tree("pay", "10.0.0.9", T.toEpochMilli(), "SQL", "s", "0", 200L))

        then:
        store.bucket(SeriesKey.problem("order", "SLOW_SQL", "s"), T).count() == 1
        store.bucket(SeriesKey.problem("pay", "SLOW_SQL", "s"), T).count() == 1
    }

    def "Problem 分类枚举完整覆盖五类"() {
        expect:
        ProblemCategory.values()*.name() as Set ==
                ["EXCEPTION", "SLOW_URL", "SLOW_SQL", "SLOW_CALL", "SLOW_CACHE"] as Set
        ProblemCategory.EXCEPTION.slow() == false
        ProblemCategory.SLOW_URL.slow() && ProblemCategory.SLOW_SQL.slow()
        ProblemCategory.SLOW_CALL.slow() && ProblemCategory.SLOW_CACHE.slow()
    }

    @Unroll
    def "阈值 0 时任何非零耗时都是慢类"() {
        given:
        def strict = new ProblemAnalyzer(store, 0, 0, 0, 0)

        when:
        strict.analyze(AnalysisFixtures.tree("order", "10.0.0.8", T.toEpochMilli(), "SQL", "s", "0", 1L))

        then:
        store.bucket(problemKey("SLOW_SQL", "s"), T).count() == 1
    }
}
