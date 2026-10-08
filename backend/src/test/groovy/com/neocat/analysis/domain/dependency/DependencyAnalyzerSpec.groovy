package com.neocat.analysis.domain.dependency

import com.neocat.analysis.domain.analyzer.AnalysisFixtures
import com.neocat.analysis.domain.bucket.SeriesKey
import com.neocat.analysis.domain.bucket.SeriesKind

import spock.lang.Specification

import java.time.Instant
import com.neocat.analysis.infra.store.InMemoryHourlyReportStore
import com.neocat.ingest.domain.tree.NodeKind
import com.neocat.ingest.domain.tree.RawNode
import com.neocat.ingest.domain.tree.RemoteCallValue

/**
 * G6 任务39（红）：依赖边分析。
 * 对应 PRD 04 §6（依赖对象）、§7（依赖采集与缺失）、§8（依赖页面）、§9（验收）。
 */
class DependencyAnalyzerSpec extends Specification {

    static final Instant T = Instant.parse("2026-09-24T04:23:41Z")

    InMemoryHourlyReportStore store
    DependencyAnalyzer analyzer

    def setup() {
        store = new InMemoryHourlyReportStore()
        analyzer = new DependencyAnalyzer(store)
    }

    def downstreamKey(String upstream, String downstream) {
        SeriesKey.of(upstream, SeriesKind.DEPENDENCY, "DOWNSTREAM", downstream)
    }

    def upstreamKey(String downstream, String upstream) {
        SeriesKey.of(downstream, SeriesKind.DEPENDENCY, "UPSTREAM", upstream)
    }

    def "分析器声明自己的域名为 dependency"() {
        expect:
        analyzer.domain() == "dependency"
    }

    // ── 边产生 ───────────────────────────────────────────────

    def "调用方记录的远程调用产生一条依赖边"() {
        when:
        analyzer.analyze(AnalysisFixtures.remoteCallTree("order", "10.0.0.8", T.toEpochMilli(),
                "pay", "RPC", "0", 45L))

        then:
        store.bucket(downstreamKey("order", "pay"), T).count() == 1
    }

    def "依赖边同时写入双向序列，使上下游列表都能查询"() {
        when:
        analyzer.analyze(AnalysisFixtures.remoteCallTree("order", "10.0.0.8", T.toEpochMilli(),
                "pay", "RPC", "0", 45L))

        then: "上游 order 的下游列表含 pay"
        store.bucket(downstreamKey("order", "pay"), T).count() == 1

        and: "下游 pay 的上游列表含 order"
        store.bucket(upstreamKey("pay", "order"), T).count() == 1
    }

    // ── 下游缺失不影响边 ─────────────────────────────────────

    def "下游 MessageTree 未到达时依赖边仍计入调用次数"() {
        when: "只上报了调用方树，pay 从未上报"
        analyzer.analyze(AnalysisFixtures.remoteCallTree("order", "10.0.0.8", T.toEpochMilli(),
                "pay", "RPC", "0", 45L))

        then: "边依然产生"
        store.bucket(downstreamKey("order", "pay"), T).count() == 1
        and: "目录中并没有 pay 上报痕迹（此处只验证分析器不依赖下游）"
        store.seriesKeys().every { it.getService() in ["order", "pay"] }
    }

    def "多次跨服务调用累加到同一依赖边"() {
        when:
        3.times {
            analyzer.analyze(AnalysisFixtures.remoteCallTree("order", "10.0.0.8", T.toEpochMilli(),
                    "pay", "RPC", "0", 10L))
        }

        then:
        store.bucket(downstreamKey("order", "pay"), T).count() == 3
        store.bucket(upstreamKey("pay", "order"), T).count() == 3
    }

    // ── 失败与耗时 ───────────────────────────────────────────

    def "能统计调用方可观测到的失败信息"() {
        when:
        analyzer.analyze(AnalysisFixtures.remoteCallTree("order", "10.0.0.8", T.toEpochMilli(),
                "pay", "RPC", "1", 45L))

        then:
        def bucket = store.bucket(downstreamKey("order", "pay"), T)
        bucket.count() == 1
        bucket.failCount() == 1
    }

    def "能统计调用方可观测到的耗时"() {
        when:
        analyzer.analyze(AnalysisFixtures.remoteCallTree("order", "10.0.0.8", T.toEpochMilli(),
                "pay", "RPC", "0", 45L))
        analyzer.analyze(AnalysisFixtures.remoteCallTree("order", "10.0.0.8", T.toEpochMilli(),
                "pay", "RPC", "0", 55L))

        then:
        def bucket = store.bucket(downstreamKey("order", "pay"), T)
        bucket.durationSum() == 100
        bucket.averageDuration() == 50.0d
        bucket.distribution().percentile(0.99d) != null
    }

    def "失败率按边统计"() {
        when:
        analyzer.analyze(AnalysisFixtures.remoteCallTree("order", "10.0.0.8", T.toEpochMilli(), "pay", "RPC", "0", 1L))
        analyzer.analyze(AnalysisFixtures.remoteCallTree("order", "10.0.0.8", T.toEpochMilli(), "pay", "RPC", "0", 1L))
        analyzer.analyze(AnalysisFixtures.remoteCallTree("order", "10.0.0.8", T.toEpochMilli(), "pay", "RPC", "1", 1L))

        then:
        def bucket = store.bucket(downstreamKey("order", "pay"), T)
        bucket.count() == 3
        bucket.failureRate() == 0.333333

    }

    // ── 调用方未观测则无边 ───────────────────────────────────

    def "调用方没有记录远程调用时不产生依赖边"() {
        when: "只有普通 Transaction 节点，没有 RemoteCall"
        analyzer.analyze(AnalysisFixtures.tree("order", "10.0.0.8", T.toEpochMilli(), "URL", "/a", "0", 10L))

        then:
        store.seriesKeys().isEmpty()
    }

    def "Event / Metric / Heartbeat 节点不产生依赖边"() {
        when:
        analyzer.analyze(AnalysisFixtures.eventTree("order", "10.0.0.8", T.toEpochMilli(), "business", "e", "0"))
        analyzer.analyze(AnalysisFixtures.metricTree("order", "10.0.0.8", T.toEpochMilli(), "m", 1.0d))
        analyzer.analyze(AnalysisFixtures.heartbeatTree("order", "10.0.0.8", T.toEpochMilli()))

        then:
        store.seriesKeys().isEmpty()
    }

    // ── 边的方向与身份 ───────────────────────────────────────

    def "不同方向的依赖是不同边（A→B 不等于 B→A）"() {
        when:
        analyzer.analyze(AnalysisFixtures.remoteCallTree("order", "10.0.0.8", T.toEpochMilli(), "pay", "RPC", "0", 1L))

        then:
        store.bucket(downstreamKey("order", "pay"), T).count() == 1
        store.bucket(downstreamKey("pay", "order"), T) == null
    }

    def "一个服务可以有多条下游依赖"() {
        when:
        analyzer.analyze(AnalysisFixtures.remoteCallTree("order", "10.0.0.8", T.toEpochMilli(), "pay", "RPC", "0", 1L))
        analyzer.analyze(AnalysisFixtures.remoteCallTree("order", "10.0.0.8", T.toEpochMilli(), "stock", "HTTP", "0", 1L))

        then:
        store.bucket(downstreamKey("order", "pay"), T).count() == 1
        store.bucket(downstreamKey("order", "stock"), T).count() == 1
    }

    def "同一对服务不同调用类型合并到同一条边（依赖是服务关系而非协议）"() {
        when:
        analyzer.analyze(AnalysisFixtures.remoteCallTree("order", "10.0.0.8", T.toEpochMilli(), "pay", "RPC", "0", 1L))
        analyzer.analyze(AnalysisFixtures.remoteCallTree("order", "10.0.0.8", T.toEpochMilli(), "pay", "MQ", "0", 1L))

        then:
        store.bucket(downstreamKey("order", "pay"), T).count() == 2
    }

    def "自调用不产生依赖边"() {
        when:
        analyzer.analyze(AnalysisFixtures.remoteCallTree("order", "10.0.0.8", T.toEpochMilli(), "order", "RPC", "0", 1L))

        then:
        store.seriesKeys().isEmpty()
    }

    def "缺少下游服务名的远程调用被跳过而不报错"() {
        given:
        def tree = AnalysisFixtures.treeWithTimes("order", "10.0.0.8", T.toEpochMilli(),
                [new RawNode("n-1", NodeKind.REMOTE_CALL,
                        "RPC", "unknown", "0", T.toEpochMilli(), 5L, null, null, null,
                        new RemoteCallValue(null, null, "RPC", "0"), null, Map.of())])

        when:
        analyzer.analyze(tree)

        then:
        noExceptionThrown()
        store.seriesKeys().isEmpty()
    }

    def "依赖统计按节点事件时间落入分钟桶"() {
        when:
        analyzer.analyze(AnalysisFixtures.remoteCallTree("order", "10.0.0.8", T.toEpochMilli(), "pay", "RPC", "0", 1L))

        then:
        store.bucket(downstreamKey("order", "pay"), T) != null
        store.bucket(downstreamKey("order", "pay"), T.plusSeconds(60)) == null
    }

    def "两个服务互相调用时产生两条独立边"() {
        when:
        analyzer.analyze(AnalysisFixtures.remoteCallTree("order", "10.0.0.8", T.toEpochMilli(), "pay", "RPC", "0", 1L))
        analyzer.analyze(AnalysisFixtures.remoteCallTree("pay", "10.0.1.1", T.toEpochMilli(), "order", "RPC", "0", 1L))

        then:
        store.bucket(downstreamKey("order", "pay"), T).count() == 1
        store.bucket(downstreamKey("pay", "order"), T).count() == 1
    }
}
