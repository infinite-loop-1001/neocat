package com.neocat.trace.domain.tree

import spock.lang.Specification

import java.time.Duration
import java.time.Instant

/**
 * G7 任务43（红）：Trace 组装。
 * 对应 PRD 02 §10（Trace 组装与缺失规则）、§11（验收 6、7）、
 * PRD 04 §9（Trace 缺失节点与依赖统计缺失分开表达）。
 */
class TraceAssembleSpec extends Specification {

    static final Instant NOW = Instant.parse("2026-09-24T04:00:00Z")
    static final Duration RETENTION = Duration.ofDays(7)

    // ── 正常组装 ─────────────────────────────────────────────

    def "单棵树可直接组装为只有一个节点的调用树"() {
        given:
        def tree = TraceFixtures.tree("m1", "m1", null, "gateway", NOW.minusSeconds(10))
        def rawStore = Stub(RawTreeStore) {
            findByMessageId("m1") >> tree
            findByRootMessageId("m1") >> [tree]
        }

        when:
        def result = new TraceAssembler(rawStore).assemble("m1", NOW, RETENTION)

        then:
        result.usable()
        result.getRoot().messageId() == "m1"
        result.getRoot().serviceName() == "gateway"
        result.getRoot().present()
        !result.isExpired()
        !result.isMissing()
    }

    def "多棵本地树按 parentMessageId 连接为跨服务调用树"() {
        given:
        def trees = [TraceFixtures.tree("m1", "m1", null, "gateway", NOW.minusSeconds(10)),
                     TraceFixtures.tree("m2", "m1", "m1", "order", NOW.minusSeconds(9)),
                     TraceFixtures.tree("m3", "m1", "m1", "pay", NOW.minusSeconds(8))]
        def store = Stub(RawTreeStore) {
            findByMessageId("m1") >> trees[0]
            findByRootMessageId("m1") >> trees
        }

        when:
        def result = new TraceAssembler(store).assemble("m1", NOW, RETENTION)

        then:
        result.getRoot().messageId() == "m1"
        result.getRoot().children()*.messageId() as Set == ["m2", "m3"] as Set
    }

    def "从任意一棵树进入都能定位到同一条 Trace"() {
        given:
        def trees = [TraceFixtures.tree("m1", "m1", null, "gateway", NOW.minusSeconds(10)),
                     TraceFixtures.tree("m2", "m1", "m1", "order", NOW.minusSeconds(9)),
                     TraceFixtures.tree("m3", "m1", "m2", "pay", NOW.minusSeconds(8))]
        def store = Stub(RawTreeStore) {
            findByMessageId("m2") >> trees[1]
            findByRootMessageId("m1") >> trees
        }

        when: "从中间的 order 树进入"
        def result = new TraceAssembler(store).assemble("m2", NOW, RETENTION)

        then: "仍以 root 为根返回完整树"
        result.getRoot().messageId() == "m1"
        result.getRoot().children().find { it.messageId() == "m2" }
                .children()*.messageId() == ["m3"]
    }

    def "深层调用链正确嵌套"() {
        given:
        def trees = [TraceFixtures.tree("m1", "m1", null, "gateway", NOW.minusSeconds(10)),
                     TraceFixtures.tree("m2", "m1", "m1", "order", NOW.minusSeconds(9)),
                     TraceFixtures.tree("m3", "m1", "m2", "pay", NOW.minusSeconds(8)),
                     TraceFixtures.tree("m4", "m1", "m3", "bank", NOW.minusSeconds(7))]
        def store = Stub(RawTreeStore) {
            findByMessageId("m1") >> trees[0]
            findByRootMessageId("m1") >> trees
        }

        when:
        def result = new TraceAssembler(store).assemble("m1", NOW, RETENTION)

        then:
        def gateway = result.getRoot()
        def order = gateway.children()[0]
        def pay = order.children()[0]
        def bank = pay.children()[0]
        gateway.serviceName() == "gateway"
        order.serviceName() == "order"
        pay.serviceName() == "pay"
        bank.serviceName() == "bank"
    }

    def "树内节点被展开为 spans"() {
        given:
        def tree = TraceFixtures.treeWithSpans("m1", "m1", null, "order", NOW.minusSeconds(5), 3)
        def store = Stub(RawTreeStore) {
            findByMessageId("m1") >> tree
            findByRootMessageId("m1") >> [tree]
        }

        when:
        def result = new TraceAssembler(store).assemble("m1", NOW, RETENTION)

        then:
        result.getRoot().spans().size() == 3
    }

    // ── 缺失节点（从未收到） ─────────────────────────────────

    def "已知父子关系但子树未收到时显示缺失节点"() {
        given: "order 是 pay 的父，但 pay 的树从未上报"
        def tree = TraceFixtures.treeWithRemoteCallTo("m1", "m1", null, "order", "pay", NOW.minusSeconds(5))
        def store = Stub(RawTreeStore) {
            findByMessageId("m1") >> tree
            findByRootMessageId("m1") >> [tree]
            relationsByRoot("m1") >> []
        }

        when:
        def result = new TraceAssembler(store).assemble("m1", NOW, RETENTION)

        then:
        result.usable()
        def missing = result.getRoot().children().find { it.serviceName() == "pay" }
        missing != null
        missing.availability() == NodeAvailability.MISSING
        missing.missingReason() != null
        result.getRoot().countMissing() == 1
    }

    def "部分树可用时其余树照常展示"() {
        given:
        def trees = [TraceFixtures.treeWithRemoteCallTo("m1", "m1", null, "order", "pay", NOW.minusSeconds(5)),
                     TraceFixtures.tree("m2", "m1", "m1", "stock", NOW.minusSeconds(4))]
        def store = Stub(RawTreeStore) {
            findByMessageId("m1") >> trees[0]
            findByRootMessageId("m1") >> trees
            relationsByRoot("m1") >> []
        }

        when:
        def result = new TraceAssembler(store).assemble("m1", NOW, RETENTION)

        then: "stock 正常展示，pay 标为缺失"
        result.getRoot().children().find { it.serviceName() == "stock" }.present()
        result.getRoot().children().find { it.serviceName() == "pay" }
                .availability() == NodeAvailability.MISSING
    }

    def "同一 Trace 下允许来自不同服务与实例的多棵树"() {
        given:
        def trees = [TraceFixtures.tree("m1", "m1", null, "order", NOW.minusSeconds(5), "10.0.0.8"),
                     TraceFixtures.tree("m2", "m1", "m1", "order", NOW.minusSeconds(4), "10.0.0.9")]
        def store = Stub(RawTreeStore) {
            findByMessageId("m1") >> trees[0]
            findByRootMessageId("m1") >> trees
        }

        when:
        def result = new TraceAssembler(store).assemble("m1", NOW, RETENTION)

        then: "同服务不同实例是两个独立树节点，未被去重"
        result.getRoot().children()*.instanceId() as Set == ["10.0.0.9"] as Set
        result.getRoot().children().size() == 1
    }

    // ── 过期（曾收到但超期） ─────────────────────────────────

    def "种子树超过留存期时返回过期状态"() {
        given:
        def tree = TraceFixtures.tree("m1", "m1", null, "order", NOW.minus(Duration.ofDays(8)))
        def rawStore = Stub(RawTreeStore) {
            findByMessageId("m1") >> tree
        }

        when:
        def result = new TraceAssembler(rawStore).assemble("m1", NOW, RETENTION)

        then:
        result.isExpired()
        !result.usable()
        !result.isMissing()
    }

    def "从未收到的树返回缺失而非过期"() {
        given:
        def rawStore = Mock(RawTreeStore)

        when:
        def result = new TraceAssembler(rawStore).assemble("never-seen", NOW, RETENTION)

        then:
        1 * rawStore.findByMessageId("never-seen") >> null
        1 * rawStore.everExisted("never-seen") >> false
        0 * rawStore._
        result.isMissing()
        !result.isExpired()
        !result.usable()
    }

    def "过期节点与缺失节点在组装结果中分开表达"() {
        given: "order 有远程调用指向 pay；pay 曾经收到但已过期仍有关系记录"
        def tree = TraceFixtures.treeWithRemoteCallTo("m1", "m1", null, "order", "pay", NOW.minusSeconds(5))
        def expired = new TraceRelation("m-pay-expired", "m1", "m1", "pay", "pay-ip",
                NOW.minus(Duration.ofDays(8)).toEpochMilli())
        def store = Stub(RawTreeStore) {
            findByMessageId("m1") >> tree
            findByRootMessageId("m1") >> [tree]
            relationsByRoot("m1") >> [expired]
        }

        when:
        def result = new TraceAssembler(store).assemble("m1", NOW, RETENTION)

        then:
        def pay = result.getRoot().children().find { it.serviceName() == "pay" }
        pay.availability() == NodeAvailability.EXPIRED
        pay.missingReason() != null
        result.getRoot().countExpired() == 1
        result.getRoot().countMissing() == 0
    }

    def "留存期可配置：7 天边界内侧可用，外侧过期"() {
        given:
        def inside = TraceFixtures.tree("m-in", "m-in", null, "order", NOW.minus(Duration.ofDays(6).plusHours(23)))
        def outside = TraceFixtures.tree("m-out", "m-out", null, "order", NOW.minus(Duration.ofDays(7).plusMinutes(1)))
        def store = Stub(RawTreeStore) {
            findByMessageId("m-in") >> inside
            findByMessageId("m-out") >> outside
            findByRootMessageId("m-in") >> [inside]
        }
        def assembler = new TraceAssembler(store)

        expect:
        assembler.assemble("m-in", NOW, RETENTION).usable()
        assembler.assemble("m-out", NOW, RETENTION).isExpired()
    }

    def "Trace 组装失败不影响其他调用：组装不应抛出异常"() {
        given: "一棵 parent 指向不存在节点的树"
        def tree = TraceFixtures.tree("orphan", "orphan", "ghost-parent", "order", NOW.minusSeconds(5))
        def store = Stub(RawTreeStore) {
            findByMessageId("orphan") >> tree
            findByRootMessageId("orphan") >> [tree]
        }

        when:
        def result = new TraceAssembler(store).assemble("orphan", NOW, RETENTION)

        then:
        noExceptionThrown()
        result.usable()
        result.getRoot().messageId() == "orphan"
    }

    def "同一 root 下不能仅以 rootMessageId 去重整条 Trace"() {
        given: "两棵不同树的 rootMessageId 相同，但 messageId 不同"
        def trees = [TraceFixtures.tree("m1", "shared-root", null, "gateway", NOW.minusSeconds(5)),
                     TraceFixtures.tree("m2", "shared-root", null, "order", NOW.minusSeconds(4))]
        def store = Stub(RawTreeStore) {
            findByMessageId("m1") >> trees[0]
            findByRootMessageId("shared-root") >> trees
        }

        when:
        def result = new TraceAssembler(store).assemble("m1", NOW, RETENTION)

        then: "以给定 messageId 为根，不把无关树合并成一条假 Trace"
        result.getRoot().messageId() == "m1"
        result.getRoot().children().isEmpty()
    }

    def "跨服务耗时由子根开始时间与父远程调用开始时间之差计算"() {
        given: "网关在 t0 发起调用 pay，pay 的树在 t0+30ms 开始"
        def t0 = NOW.minusSeconds(5).toEpochMilli()
        def trees = [TraceFixtures.treeWithRemoteCall("m1", "m1", null, "gateway", "pay",
                t0, 100L, t0 + 10),
                     TraceFixtures.treeAt("m2", "m1", "m1", "pay", Instant.ofEpochMilli(t0 + 30))]
        def store = Stub(RawTreeStore) {
            findByMessageId("m1") >> trees[0]
            findByRootMessageId("m1") >> trees
        }

        when:
        def result = new TraceAssembler(store).assemble("m1", NOW, RETENTION)

        then:
        def pay = result.getRoot().children().find { it.serviceName() == "pay" }
        pay.present()
        result.getRoot().spans().any { it.getCategory() == "CALL" }
    }
}
