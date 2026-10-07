package com.neocat.ingest.domain.validation

import com.neocat.ingest.domain.receive.IngestBatch
import com.neocat.ingest.domain.tree.MessageTree
import com.neocat.ingest.domain.tree.MetricValue
import com.neocat.ingest.domain.tree.NodeKind
import com.neocat.ingest.domain.tree.RawNode

import spock.lang.Specification
import spock.lang.Unroll

import static com.neocat.ingest.domain.validation.ValidationOutcome.*

/**
 * G5 任务21（红）：上报校验链。
 * 对应 PRD 02 §4（接收流程校验）与技术方案 04 §4。
 */
class IngestValidationSpec extends Specification {

    TreeValidator validator

    def setup() {
        validator = new TreeValidator()
    }

    static MessageTree tree(String messageId = "m-1", String service = "order",
                           String instance = "10.0.0.8", List<RawNode> nodes = null) {
        new MessageTree(service, instance, messageId, messageId, null, 1790000000000L,
                nodes == null ? [node()] : nodes)
    }

    static RawNode node(String nodeId = "n-1", NodeKind kind = NodeKind.TRANSACTION,
                       String status = "0", long durationMs = 12L, String category = "URL",
                       String name = "POST /orders", long timestamp = 1790000000000L) {
        new RawNode(nodeId, kind, category, name, status, timestamp, durationMs, null,
                null, null, null, null, Map.of())
    }

    // ── 协议版本 ─────────────────────────────────────────────

    @Unroll
    def "协议版本 '#version' 不被支持"() {
        when:
        def outcome = validator.validateBatch(new IngestBatch(version, [tree()]), 100)

        then:
        !outcome.isValid()
        outcome.getCode() == UNSUPPORTED_VERSION

        where:
        version << ["2.0", "0.9", "", null]
    }

    def "协议版本 1.0 可接受"() {
        when:
        def outcome = validator.validateBatch(new IngestBatch("1.0", [tree()]), 100)

        then:
        outcome.isValid()
    }

    // ── 批次规模 ─────────────────────────────────────────────

    def "批次树数超过上限被拒"() {
        given:
        def trees = (1..201).collect { tree("m-$it") }

        when:
        def outcome = validator.validateBatch(new IngestBatch("1.0", trees), 100)

        then:
        !outcome.isValid()
        outcome.getCode() == BATCH_TOO_LARGE
    }

    def "批次树数正好等于上限时通过"() {
        given:
        def trees = (1..200).collect { tree("m-$it") }

        when:
        def outcome = validator.validateBatch(new IngestBatch("1.0", trees), 100)

        then:
        outcome.isValid()
    }

    def "批次字节超过 1MiB 被拒"() {
        when:
        def outcome = validator.validateBatch(new IngestBatch("1.0", [tree()]), 1048577)

        then:
        !outcome.isValid()
        outcome.getCode() == BATCH_TOO_LARGE
    }

    def "批次字节正好 1MiB 时通过"() {
        when:
        def outcome = validator.validateBatch(new IngestBatch("1.0", [tree()]), 1048576)

        then:
        outcome.isValid()
    }

    def "空批次被拒"() {
        when:
        def outcome = validator.validateBatch(new IngestBatch("1.0", []), 10)

        then:
        !outcome.isValid()
        outcome.getCode() == BATCH_TOO_LARGE
    }

    // ── 单树必填字段 ─────────────────────────────────────────

    @Unroll
    def "必填字段缺失被拒：#field"() {
        when:
        def outcome = validator.validateTree(bad)

        then:
        !outcome.isValid()
        outcome.getCode() == MALFORMED_TREE

        where:
        field          | bad
        "serviceName"  | tree().with { new MessageTree(null, it.getInstanceId(), it.getMessageId(), it.getRootMessageId(), it.getParentMessageId(), it.getTreeTimestamp(), it.getNodes()) }
        "serviceName空" | tree().with { new MessageTree("  ", it.getInstanceId(), it.getMessageId(), it.getRootMessageId(), it.getParentMessageId(), it.getTreeTimestamp(), it.getNodes()) }
        "instanceId"   | tree().with { new MessageTree(it.getServiceName(), null, it.getMessageId(), it.getRootMessageId(), it.getParentMessageId(), it.getTreeTimestamp(), it.getNodes()) }
        "messageId"    | tree().with { new MessageTree(it.getServiceName(), it.getInstanceId(), null, it.getRootMessageId(), it.getParentMessageId(), it.getTreeTimestamp(), it.getNodes()) }
    }

    def "字段长度超过 256 被拒"() {
        given:
        def longName = "x" * 257
        def bad = tree().with {
            new MessageTree(longName, it.getInstanceId(), it.getMessageId(), it.getRootMessageId(),
                    it.getParentMessageId(), it.getTreeTimestamp(), it.getNodes())
        }

        when:
        def outcome = validator.validateTree(bad)

        then:
        !outcome.isValid()
        outcome.getCode() == MALFORMED_TREE
    }

    def "字段长度正好 256 通过"() {
        given:
        def exact = "x" * 256
        def ok = tree().with {
            new MessageTree(exact, it.getInstanceId(), it.getMessageId(), it.getRootMessageId(),
                    it.getParentMessageId(), it.getTreeTimestamp(), it.getNodes())
        }

        when:
        def outcome = validator.validateTree(ok)

        then:
        outcome.isValid()
    }

    // ── 节点数量 ─────────────────────────────────────────────

    def "单树节点数超过 3000 被拒"() {
        given:
        def nodes = (1..3001).collect { node("n-$it") }
        def bad = new MessageTree("order", "10.0.0.8", "m-1", "m-1", null, 1790000000000L, nodes)

        when:
        def outcome = validator.validateTree(bad)

        then:
        !outcome.isValid()
        outcome.getCode() == TREE_TOO_LARGE
    }

    def "单树节点数正好 3000 通过"() {
        given:
        def nodes = (1..3000).collect { node("n-$it") }
        def ok = new MessageTree("order", "10.0.0.8", "m-1", "m-1", null, 1790000000000L, nodes)

        when:
        def outcome = validator.validateTree(ok)

        then:
        outcome.isValid()
    }

    def "空节点树被拒"() {
        when:
        def outcome = validator.validateTree(tree("m-1", "order", "10.0.0.8", []))

        then:
        !outcome.isValid()
        outcome.getCode() == MALFORMED_TREE
    }

    // ── 节点唯一性与数值 ─────────────────────────────────────

    def "nodeId 在树内重复被拒"() {
        given:
        def bad = tree("m-1", "order", "10.0.0.8", [node("same"), node("same")])

        when:
        def outcome = validator.validateTree(bad)

        then:
        !outcome.isValid()
        outcome.getCode() == MALFORMED_TREE
    }

    def "durationMs 为负数被拒"() {
        given:
        def bad = tree("m-1", "order", "10.0.0.8", [node("n-1", NodeKind.TRANSACTION, "0", -1L)])

        when:
        def outcome = validator.validateTree(bad)

        then:
        !outcome.isValid()
        outcome.getCode() == MALFORMED_TREE
    }

    def "durationMs 为 0 合法"() {
        given:
        def ok = tree("m-1", "order", "10.0.0.8", [node("n-1", NodeKind.TRANSACTION, "0", 0L)])

        when:
        def outcome = validator.validateTree(ok)

        then:
        outcome.isValid()
    }

    def "Metric 数值为 NaN 被拒"() {
        given:
        def metric = new MetricValue("order.amount", Double.NaN, Map.of())
        def metricNode = new RawNode("n-1", NodeKind.METRIC, "order.amount", "order.amount", "0",
                1790000000000L, 0L, null, metric, null, null, null, Map.of())
        def bad = tree("m-1", "order", "10.0.0.8", [metricNode])

        when:
        def outcome = validator.validateTree(bad)

        then:
        !outcome.isValid()
        outcome.getCode() == MALFORMED_TREE
    }

    def "Metric 数值为无穷大被拒"() {
        given:
        def metric = new MetricValue("order.amount", Double.POSITIVE_INFINITY, Map.of())
        def metricNode = new RawNode("n-1", NodeKind.METRIC, "order.amount", "order.amount", "0",
                1790000000000L, 0L, null, metric, null, null, null, Map.of())
        def bad = tree("m-1", "order", "10.0.0.8", [metricNode])

        when:
        def outcome = validator.validateTree(bad)

        then:
        !outcome.isValid()
        outcome.getCode() == MALFORMED_TREE
    }

    def "合法的完整树通过校验"() {
        given:
        def trees = [
                tree("m-1"),
                new MessageTree("pay", "10.0.1.1", "m-2", "m-1", "m-1", 1790000000000L,
                        [node("n-1", NodeKind.TRANSACTION, "0", 5L, "URL", "POST /pay")])
        ]

        when:
        def outcome = validator.validateBatch(new IngestBatch("1.0", trees), 500)

        then:
        outcome.isValid()
    }
}
