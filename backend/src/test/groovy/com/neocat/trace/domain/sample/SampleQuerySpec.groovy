package com.neocat.trace.domain.sample

import com.neocat.trace.domain.tree.RawTreeStore
import com.neocat.trace.domain.tree.TraceNode
import com.neocat.trace.domain.tree.TraceTree

import spock.lang.Specification

import java.time.Duration
import java.time.Instant

/**
 * G7 任务45（红）：调用取样查询。
 * 对应 PRD 03 §11（最近30条、多行、按事件时间倒序、受筛选约束、过期不可下钻）、
 * PRD 00 §12（每行最近30条替代最多10条）。
 */
class SampleQuerySpec extends Specification {

    static final Instant NOW = Instant.parse("2026-09-24T04:00:00Z")
    static final Duration RETENTION = Duration.ofDays(7)
    static final long FROM = NOW.minusSeconds(3600).toEpochMilli()
    static final long TO = NOW.toEpochMilli()

    RawTreeStore store
    SampleService service

    def setup() {
        store = Stub(RawTreeStore)
        service = new SampleService(store)
    }

    def candidates(String serviceName, long from, long to, List<TraceTree> trees) {
        store.findByServiceAndTimeRange(serviceName, from, to) >>
                trees.findAll { (serviceName == null || it.getServiceName() == serviceName) &&
                        it.getTreeTimestamp() >= from && it.getTreeTimestamp() < to }
    }

    def treeWithUrlSpan(String messageId, String service, Instant at, String url,
                        String status = "0", long durationMs = 10L) {
        new TraceTree(messageId, messageId, null, service, service + "-ip", at.toEpochMilli(),
                "fp-" + messageId,
                [new TraceNode("n-1", "TRANSACTION", "URL", url, status, at.toEpochMilli(),
                        durationMs, null, [:])])
    }

    // ── 条数与顺序 ───────────────────────────────────────────

    def "默认返回最近 30 条"() {
        given:
        candidates('order', FROM, TO, (1..50).collect { i ->
            treeWithUrlSpan("m$i", "order", NOW.minusSeconds(i), "/a")
        })

        when:
        def samples = service.samples(SampleQuery.of("order", "URL", "/a", FROM, TO), NOW, RETENTION)

        then:
        samples.size() == 30
    }

    def "按事件时间倒序返回（最新在前）"() {
        given:
        candidates('order', FROM, TO, [treeWithUrlSpan("old", "order", NOW.minusSeconds(300), "/a"),
                treeWithUrlSpan("new", "order", NOW.minusSeconds(10), "/a"),
                treeWithUrlSpan("mid", "order", NOW.minusSeconds(150), "/a")])

        when:
        def samples = service.samples(SampleQuery.of("order", "URL", "/a", FROM, TO), NOW, RETENTION)

        then:
        samples*.getMessageId() == ["new", "mid", "old"]
    }

    def "更早的异常调用不插入最新顺序（严格按时间倒序，不做故障优先排序）"() {
        given:
        candidates('order', FROM, TO, [treeWithUrlSpan("ok-new", "order", NOW.minusSeconds(10), "/a", "0"),
                treeWithUrlSpan("fail-old", "order", NOW.minusSeconds(500), "/a", "ERROR"),
                treeWithUrlSpan("ok-mid", "order", NOW.minusSeconds(60), "/a", "0")])

        when:
        def samples = service.samples(SampleQuery.of("order", "URL", "/a", FROM, TO), NOW, RETENTION)

        then: "失败的旧调用排在最后，而不是被提到最前"
        samples*.getMessageId() == ["ok-new", "ok-mid", "fail-old"]
    }

    def "不足 30 条时返回实际条数"() {
        given:
        def rawStore = Stub(RawTreeStore) {
            findByServiceAndTimeRange("order", FROM, TO) >>
                    [treeWithUrlSpan("m1", "order", NOW.minusSeconds(10), "/a")]
        }

        expect:
        new SampleService(rawStore).samples(SampleQuery.of("order", "URL", "/a", FROM, TO), NOW, RETENTION).size() == 1
    }

    def "limit 可显式指定"() {
        given:
        candidates('order', FROM, TO, (1..20).collect { i ->
            treeWithUrlSpan("m$i", "order", NOW.minusSeconds(i), "/a")
        })

        when:
        def samples = service.samples(
                new SampleQuery("order", "URL", "/a", null, FROM, TO, null, 5), NOW, RETENTION)

        then:
        samples.size() == 5
    }

    // ── 筛选约束 ─────────────────────────────────────────────

    def "按服务筛选"() {
        given:
        candidates('order', FROM, TO, [treeWithUrlSpan("m1", "order", NOW.minusSeconds(10), "/a")])
        candidates('pay', FROM, TO, [treeWithUrlSpan("m2", "pay", NOW.minusSeconds(10), "/a")])

        expect:
        service.samples(SampleQuery.of("order", "URL", "/a", FROM, TO), NOW, RETENTION)*.getMessageId() == ["m1"]
        service.samples(SampleQuery.of("pay", "URL", "/a", FROM, TO), NOW, RETENTION)*.getMessageId() == ["m2"]
    }

    def "按 Name 筛选"() {
        given:
        candidates('order', FROM, TO, [treeWithUrlSpan("m1", "order", NOW.minusSeconds(10), "/a"),
                treeWithUrlSpan("m2", "order", NOW.minusSeconds(10), "/b")])

        expect:
        service.samples(SampleQuery.of("order", "URL", "/a", FROM, TO), NOW, RETENTION)*.getMessageId() == ["m1"]
    }

    def "按时间范围筛选：范围外不返回"() {
        given:
        candidates('order', FROM, TO, [treeWithUrlSpan("inside", "order", NOW.minusSeconds(600), "/a")])

        expect:
        service.samples(SampleQuery.of("order", "URL", "/a", FROM, TO), NOW, RETENTION)*.getMessageId() == ["inside"]
    }

    def "按实例筛选"() {
        given:
        def trees = [new TraceTree("m1", "m1", null, "order", "10.0.0.8", NOW.minusSeconds(10).toEpochMilli(),
                "fp1", [new TraceNode("n-1", "TRANSACTION", "URL", "/a", "0",
                NOW.minusSeconds(10).toEpochMilli(), 10L, null, [:])]),
                new TraceTree("m2", "m2", null, "order", "10.0.0.9", NOW.minusSeconds(10).toEpochMilli(),
                "fp2", [new TraceNode("n-1", "TRANSACTION", "URL", "/a", "0",
                NOW.minusSeconds(10).toEpochMilli(), 10L, null, [:])])]
        candidates('order', FROM, TO, trees)

        when:
        def samples = service.samples(
                new SampleQuery("order", "URL", "/a", "10.0.0.9", FROM, TO, null, 30), NOW, RETENTION)

        then:
        samples*.getMessageId() == ["m2"]
    }

    def "按分类筛选：URL 查询不返回 SQL 调用"() {
        given:
        def trees = [new TraceTree("m-url", "m-url", null, "order", "ip", NOW.minusSeconds(10).toEpochMilli(),
                "fp1", [new TraceNode("n-1", "TRANSACTION", "URL", "/a", "0",
                NOW.minusSeconds(10).toEpochMilli(), 10L, null, [:])]),
                new TraceTree("m-sql", "m-sql", null, "order", "ip", NOW.minusSeconds(10).toEpochMilli(),
                "fp2", [new TraceNode("n-1", "TRANSACTION", "SQL", "select1", "0",
                NOW.minusSeconds(10).toEpochMilli(), 10L, null, [:])])]
        candidates('order', FROM, TO, trees)

        expect:
        service.samples(SampleQuery.of("order", "URL", "/a", FROM, TO), NOW, RETENTION)*.getMessageId() == ["m-url"]
        service.samples(SampleQuery.of("order", "SQL", "select1", FROM, TO), NOW, RETENTION)*.getMessageId() == ["m-sql"]
    }

    def "Problem 查询按分类筛选"() {
        given:
        candidates('order', FROM, TO, [new TraceTree("m-ex", "m-ex", null, "order", "ip", NOW.minusSeconds(10).toEpochMilli(),
                "fp1", [new TraceNode("n-1", "TRANSACTION", "EXCEPTION", "java.lang.NullPointerException",
                "ERROR", NOW.minusSeconds(10).toEpochMilli(), 10L, "boom", [:])])])

        when:
        def samples = service.samples(
                new SampleQuery("order", null, null, null, FROM, TO, "EXCEPTION", 30), NOW, RETENTION)

        then:
        samples.size() == 1
        samples[0].getMessageId() == "m-ex"
    }

    // ── 取样条目内容 ─────────────────────────────────────────

    def "取样条目包含下钻所需的 messageId、时间、耗时、状态与摘要"() {
        given:
        def rawStore = Stub(RawTreeStore) {
            findByServiceAndTimeRange("order", FROM, TO) >>
                    [treeWithUrlSpan("m1", "order", NOW.minusSeconds(10), "/a", "ERROR", 812L)]
        }

        when:
        def sample = new SampleService(rawStore).samples(SampleQuery.of("order", "URL", "/a", FROM, TO), NOW, RETENTION)[0]

        then:
        sample.getMessageId() == "m1"
        sample.getTimestamp() == NOW.minusSeconds(10).toEpochMilli()
        sample.getDurationMs() == 812L
        sample.getStatus() == "ERROR"
        sample.failed()
        sample.getSummary() == "/a"
    }

    def "留存期内的样本可下钻"() {
        given:
        def rawStore = Stub(RawTreeStore) {
            findByServiceAndTimeRange("order", FROM, TO) >>
                    [treeWithUrlSpan("m1", "order", NOW.minusSeconds(3600), "/a")]
        }

        expect:
        new SampleService(rawStore).samples(SampleQuery.of("order", "URL", "/a", FROM, TO), NOW, RETENTION)[0].isTraceAvailable()
    }

    def "超过留存期的原始树标记为不可下钻"() {
        given: "分钟桶仍可查（此处用较宽的查询范围模拟汇总仍存在）"
        def old = NOW.minus(Duration.ofDays(7).plusHours(1))
        candidates('order', old.minusSeconds(60).toEpochMilli(), TO,
                [treeWithUrlSpan("m-old", "order", old, "/a")])

        when:
        def samples = service.samples(
                SampleQuery.of("order", "URL", "/a", old.minusSeconds(60).toEpochMilli(), TO), NOW, RETENTION)

        then: "汇总仍返回该条，但不可打开 Trace"
        samples*.getMessageId() == ["m-old"]
        samples[0].isTraceAvailable() == false
    }

    def "没有任何候选时返回空列表而不是报错"() {
        given:
        def rawStore = Mock(RawTreeStore)

        when:
        def samples = new SampleService(rawStore).samples(
                SampleQuery.of("ghost", "URL", "/a", FROM, TO), NOW, RETENTION)

        then:
        1 * rawStore.findByServiceAndTimeRange("ghost", FROM, TO) >> []
        0 * rawStore._
        samples.isEmpty()
    }

    def "一期默认取样条数为 30（替代旧规格的最多 10 条）"() {
        expect:
        SampleQuery.DEFAULT_LIMIT == 30
        SampleQuery.of("order", "URL", "/a", FROM, TO).getLimit() == 30
    }

    def "无匹配 span 的树不产生取样条目"() {
        given:
        def rawStore = Stub(RawTreeStore) {
            findByServiceAndTimeRange("order", FROM, TO) >>
                    [treeWithUrlSpan("m1", "order", NOW.minusSeconds(10), "/other")]
        }

        expect:
        new SampleService(rawStore).samples(SampleQuery.of("order", "URL", "/a", FROM, TO), NOW, RETENTION).isEmpty()
    }
}
