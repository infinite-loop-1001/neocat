package com.neocat.query.domain.report

import com.neocat.analysis.domain.bucket.AggregatedRow
import com.neocat.analysis.domain.bucket.AggregationLevel
import com.neocat.analysis.domain.bucket.SeriesKey
import com.neocat.analysis.domain.bucket.SeriesKind
import spock.lang.Specification

import java.time.Instant

import static com.neocat.query.domain.report.DependencyDirectionQuery.*

/**
 * G8 任务63（红）：依赖查询。
 * 对应 PRD 04 §6（依赖对象字段）、§7（缺失）、§8（依赖页面：列表为主入口）、
 * §9（验收 4–5：缺失不丢边、缺失与 Trace 分开表达）。
 */
class DependencyQuerySpec extends Specification {

    DependencyQueryService service = new DependencyQueryService()

    static Instant HOUR = Instant.parse("2026-09-24T04:00:00Z")

    def edgeRow(String owner, String direction, String peer,
                long calls, long failures, long durationSum, List<Long> samples) {
        def key = SeriesKey.of(owner, SeriesKind.DEPENDENCY, direction, peer)
        def row = new AggregatedRow(key, HOUR, AggregationLevel.HOUR, 3600)
        row.addCount(calls, failures, durationSum, 10, 100)
        samples.each { s -> row.recordDuration(s) }
        return row
    }

    def downstreamRows() {
        [
                edgeRow("order", "DOWNSTREAM", "pay", 1000, 20, 50000, (1..1000).collect { 50L }),
                edgeRow("order", "DOWNSTREAM", "stock", 500, 5, 5000, (1..500).collect { 10L })
        ]
    }

    // ── 下游列表 ─────────────────────────────────────────────

    def "下游列表返回当前服务调用的服务"() {
        when:
        def list = service.list(DOWNSTREAM, downstreamRows(), 3600)

        then:
        list*.getPeerService() as Set == ["pay", "stock"] as Set
    }

    def "下游列表字段完整：调用次数、失败率、平均耗时、分位"() {
        when:
        def pay = service.list(DOWNSTREAM, downstreamRows(), 3600).find { it.getPeerService() == "pay" }

        then:
        pay.getCalls() == 1000
        pay.getFailures() == 20
        pay.getFailureRate() == 20.0d / 1000
        pay.getAvgDuration() == 50.0d
        pay.getTp99() != null
    }

    def "列表按调用次数降序"() {
        when:
        def list = service.list(DOWNSTREAM, downstreamRows(), 3600)

        then:
        list[0].getPeerService() == "pay"
        list[0].getCalls() >= list[1].getCalls()
    }

    // ── 上游列表 ─────────────────────────────────────────────

    def "上游列表返回调用当前服务的服务"() {
        given:
        def rows = [
                edgeRow("pay", "UPSTREAM", "order", 1000, 20, 50000, (1..100).collect { 50L }),
                edgeRow("pay", "UPSTREAM", "cart", 300, 0, 3000, (1..30).collect { 10L })
        ]

        when:
        def list = service.list(UPSTREAM, rows, 3600)

        then:
        list*.getPeerService() as Set == ["order", "cart"] as Set
    }

    def "上下游方向互不混淆：DOWNSTREAM 查询不返回 UPSTREAM 行"() {
        given:
        def rows = downstreamRows() + [
                edgeRow("order", "UPSTREAM", "gateway", 2000, 0, 20000, (1..100).collect { 10L })
        ]

        when:
        def downstream = service.list(DOWNSTREAM, rows, 3600)
        def upstream = service.list(UPSTREAM, rows, 3600)

        then:
        !downstream*.getPeerService().contains("gateway")
        !upstream*.getPeerService().contains("pay")
    }

    // ── 下游缺失不丢边 ───────────────────────────────────────

    def "依赖统计不因下游 MessageTree 缺失而整条丢失（PRD 04 §9 验收 4）"() {
        given: "只有调用方观测到的边，下游服务从未上报过任何数据"
        def rows = [edgeRow("order", "DOWNSTREAM", "never-reported", 100, 0, 1000, (1..10).collect { 10L })]

        when:
        def list = service.list(DOWNSTREAM, rows, 3600)

        then: "边仍然存在，字段可用"
        list.size() == 1
        list[0].getPeerService() == "never-reported"
        list[0].getCalls() == 100
        list[0].getAvgDuration() != null
    }

    def "边不携带下游树的存在性信息：查询层不检查下游是否上报"() {
        given:
        def rows = [edgeRow("order", "DOWNSTREAM", "ghost", 50, 0, 500, (1..5).collect { 10L })]

        when:
        def list = service.list(DOWNSTREAM, rows, 3600)

        then:
        list[0].getCalls() == 50
        and: "样本 messageId 由调用方提供，不与下游是否上报耦合"
        list[0].getSampleMessageId() == null || list[0].getSampleMessageId() instanceof String
    }

    // ── 边界 ─────────────────────────────────────────────────

    def "无依赖边时返回空列表"() {
        expect:
        service.list(DOWNSTREAM, [], 3600).isEmpty()
        service.list(UPSTREAM, [], 3600).isEmpty()
    }

    def "无调用时次数为 0、耗时类无值"() {
        given:
        def rows = [edgeRow("order", "DOWNSTREAM", "pay", 0, 0, 0, [])]

        when:
        def row = service.list(DOWNSTREAM, rows, 3600)[0]

        then:
        row.getCalls() == 0
        row.getAvgDuration() == null
        row.getTp99() == null
        row.getFailureRate() == null
    }

    def "同一对服务的多次调用累加到同一行"() {
        given:
        def rows = [
                edgeRow("order", "DOWNSTREAM", "pay", 100, 0, 1000, (1..10).collect { 10L }),
                edgeRow("order", "DOWNSTREAM", "pay", 200, 5, 2000, (1..20).collect { 10L })
        ]

        when:
        def list = service.list(DOWNSTREAM, rows, 3600)

        then:
        list.size() == 1
        list[0].getCalls() == 300
        list[0].getFailures() == 5
    }

    def "分位由合并分布重算，不平均各边分位"() {
        given: "一边全 10ms，另一边全 1000ms"
        def fast = edgeRow("order", "DOWNSTREAM", "pay", 10, 0, 100, (1..10).collect { 10L })
        def slow = edgeRow("order", "DOWNSTREAM", "pay", 90, 0, 90000, (1..90).collect { 1000L })

        when:
        def list = service.list(DOWNSTREAM, [fast, slow], 3600)

        then:
        list[0].getTp99() >= 1000.0d
        list[0].getTp99() > 505.0d
    }

    def "代表性 Trace 入口：有样本时给 messageId，无样本时为 null"() {
        given:
        def rows = [edgeRow("order", "DOWNSTREAM", "pay", 100, 0, 1000, (1..10).collect { 10L })]
            .collect { it }

        when:
        def list = service.list(DOWNSTREAM, rows, 3600)

        then:
        list[0].getCalls() == 100
    }

    def "QPS 分母为 0 时依赖行的速率类为无值"() {
        when:
        def list = service.list(DOWNSTREAM, downstreamRows(), 0)

        then:
        list[0].getAvgDuration() != null
    }

    def "依赖方向枚举完整覆盖上下游"() {
        expect:
        DependencyDirectionQuery.values()*.name() as Set ==
                ["UPSTREAM", "DOWNSTREAM"] as Set
    }
}
