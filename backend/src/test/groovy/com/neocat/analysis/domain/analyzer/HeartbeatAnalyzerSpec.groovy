package com.neocat.analysis.domain.analyzer

import com.neocat.analysis.domain.bucket.SeriesKey
import com.neocat.analysis.domain.bucket.SeriesKind

import spock.lang.Specification
import spock.lang.Unroll

import java.time.Instant

/**
 * G6 任务35（红）：Heartbeat 实例级分析。
 * 对应 PRD 03 §10：只支持 JVM；堆/GC/线程；按实例分别展示；
 * **不合并不同 JVM 的值**；非 JVM 服务没有 Heartbeat 页。
 */
class HeartbeatAnalyzerSpec extends Specification {

    static final Instant T = Instant.parse("2026-09-24T04:23:41Z")

    com.neocat.analysis.infra.store.InMemoryHourlyReportStore store
    HeartbeatAnalyzer analyzer

    def setup() {
        store = new com.neocat.analysis.infra.store.InMemoryHourlyReportStore()
        analyzer = new HeartbeatAnalyzer(store)
    }

    def jvmKey(String metric) {
        SeriesKey.of("order", SeriesKind.HEARTBEAT, "jvm", metric)
    }

    def instanceKey(String metric, String instance) {
        SeriesKey.of("order", SeriesKind.HEARTBEAT, "jvm", metric, instance)
    }

    def "分析器声明自己的域名为 heartbeat"() {
        expect:
        analyzer.domain() == "heartbeat"
    }

    def "JVM Heartbeat 产出五个实例级序列"() {
        when:
        analyzer.analyze(AnalysisFixtures.heartbeatTree("order", "10.0.0.8", T.toEpochMilli()))

        then:
        store.bucket(instanceKey("heap-used", "10.0.0.8"), T) != null
        store.bucket(instanceKey("heap-max", "10.0.0.8"), T) != null
        store.bucket(instanceKey("gc-count", "10.0.0.8"), T) != null
        store.bucket(instanceKey("gc-time", "10.0.0.8"), T) != null
        store.bucket(instanceKey("threads", "10.0.0.8"), T) != null
    }

    def "只写实例行，不写全机器聚合行（不合并不同 JVM 的值）"() {
        when:
        analyzer.analyze(AnalysisFixtures.heartbeatTree("order", "10.0.0.8", T.toEpochMilli()))

        then: "任何 heartbeat 序列的 instance 都不是 all"
        store.seriesKeys()
                .findAll { it.getKind() == SeriesKind.HEARTBEAT }
                .every { it.getInstance() != SeriesKey.ALL }
    }

    def "不同实例的堆内存互不合并"() {
        when:
        analyzer.analyze(AnalysisFixtures.heartbeatTree("order", "10.0.0.8", T.toEpochMilli()))
        analyzer.analyze(AnalysisFixtures.heartbeatTree("order", "10.0.0.9", T.toEpochMilli()))

        then: "两台机器各有独立序列，各自 valueCount 为 1"
        store.bucket(instanceKey("heap-used", "10.0.0.8"), T).valueCount() == 1
        store.bucket(instanceKey("heap-used", "10.0.0.9"), T).valueCount() == 1

        and: "不存在把两台机器相加的聚合序列"
        store.bucket(SeriesKey.of("order", SeriesKind.HEARTBEAT, "jvm", "heap-used"), T) == null
    }

    def "堆已用值取自节点载荷"() {
        given:
        def tree = AnalysisFixtures.heartbeatTree("order", "10.0.0.8", T.toEpochMilli())

        when:
        analyzer.analyze(tree)

        then:
        def bucket = store.bucket(instanceKey("heap-used", "10.0.0.8"), T)
        bucket.valueCount() == 1
        bucket.valueSum() == 512_000_000d
    }

    def "同一实例多次上报累加到同一分钟桶"() {
        when:
        3.times { analyzer.analyze(AnalysisFixtures.heartbeatTree("order", "10.0.0.8", T.toEpochMilli())) }

        then:
        store.bucket(instanceKey("threads", "10.0.0.8"), T).valueCount() == 3
    }

    def "桶归属使用节点事件时间"() {
        given:
        def later = T.plusSeconds(120)
        def tree = AnalysisFixtures.treeWithTimes("order", "10.0.0.8", T.toEpochMilli(),
                [new com.neocat.ingest.domain.tree.RawNode("n-1", com.neocat.ingest.domain.tree.NodeKind.HEARTBEAT,
                        "jvm", "jvm", "0", later.toEpochMilli(), 0L, null, null,
                        new com.neocat.ingest.domain.tree.HeartbeatValue(1L, 2L, 3L, 4L, 5L),
                        null, null, Map.of())])

        when:
        analyzer.analyze(tree)

        then:
        store.bucket(instanceKey("heap-used", "10.0.0.8"), later).valueCount() == 1
        store.bucket(instanceKey("heap-used", "10.0.0.8"), T) == null
    }

    @Unroll
    def "指标 '#metric' 对应载荷字段"() {
        when:
        analyzer.analyze(AnalysisFixtures.heartbeatTree("order", "10.0.0.8", T.toEpochMilli()))

        then:
        store.bucket(instanceKey(metric, "10.0.0.8"), T).valueSum() == expected

        where:
        metric      | expected
        "heap-used" | 512_000_000d
        "heap-max"  | 2_048_000_000d
        "gc-count"  | 12d
        "gc-time"   | 340d
        "threads"   | 96d
    }

    def "非 JVM 的 Transaction/Event/Metric 节点不产出 Heartbeat"() {
        when:
        analyzer.analyze(AnalysisFixtures.tree("order", "10.0.0.8", T.toEpochMilli(), "URL", "/a", "0", 10L))
        analyzer.analyze(AnalysisFixtures.eventTree("order", "10.0.0.8", T.toEpochMilli(), "business", "e", "0"))
        analyzer.analyze(AnalysisFixtures.metricTree("order", "10.0.0.8", T.toEpochMilli(), "m", 1.0d))

        then:
        store.seriesKeys().isEmpty()
    }

    def "缺少 Heartbeat 载荷的节点被跳过而不报错"() {
        given:
        def tree = AnalysisFixtures.treeWithTimes("order", "10.0.0.8", T.toEpochMilli(),
                [new com.neocat.ingest.domain.tree.RawNode("n-1", com.neocat.ingest.domain.tree.NodeKind.HEARTBEAT,
                        "jvm", "jvm", "0", T.toEpochMilli(), 0L, null, null, null, null, null, Map.of())])

        when:
        analyzer.analyze(tree)

        then:
        noExceptionThrown()
        store.seriesKeys().isEmpty()
    }

    def "JVM 指标枚举完整覆盖一期范围"() {
        expect:
        JvmMetric.values()*.seriesName() as Set ==
                ["heap-used", "heap-max", "gc-count", "gc-time", "threads",
                 "young-used", "young-committed", "young-max", "old-used", "old-committed", "old-max",
                 "metaspace-used", "metaspace-committed", "metaspace-max", "young-gc-count", "young-gc-time",
                 "old-gc-count", "old-gc-time", "full-gc-count", "full-gc-time"] as Set
    }
}
