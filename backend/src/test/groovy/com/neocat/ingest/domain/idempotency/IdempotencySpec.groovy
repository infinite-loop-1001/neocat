package com.neocat.ingest.domain.idempotency

import com.neocat.ingest.domain.tree.IngestFixtures
import com.neocat.ingest.domain.validation.FingerprintCalculator

import spock.lang.Specification

import java.time.Duration
import java.time.Instant

import static com.neocat.ingest.domain.idempotency.IdempotencyDecision.*

/**
 * G5 任务25（红）：上报幂等。
 * 对应 PRD 02 §6.1 决策表与 §11 验收。
 */
class IdempotencySpec extends Specification {

    static final Duration WINDOW = Duration.ofMinutes(120)

    IdempotencyStore store
    HistoricalFingerprintLookup historical
    IdempotencyService service
    FingerprintCalculator calculator

    def setup() {
        store = Mock(IdempotencyStore)
        historical = Mock(HistoricalFingerprintLookup)
        service = new IdempotencyService(store, historical)
        calculator = new FingerprintCalculator()
    }

    // ── 决策表 ───────────────────────────────────────────────

    def "未见过 messageId 判定为 NEW"() {
        when:
        def result = service.decide("m-1", "fp-a")

        then:
        1 * store.fingerprintOf('m-1') >> null
        1 * historical.fingerprintOf('m-1') >> null
        1 * store.remember('m-1', 'fp-a', WINDOW)
        result == NEW
    }

    def "已见过且内容相同判定为 DUPLICATE"() {
        when:
        def result = service.decide("m-1", "fp-a")

        then:
        1 * store.fingerprintOf('m-1') >> 'fp-a'
        0 * historical._
        0 * store.remember(_, _, _)
        result == DUPLICATE
    }

    def "已见过但内容不同判定为 CONFLICT"() {
        when:
        def result = service.decide("m-1", "fp-b")

        then:
        1 * store.fingerprintOf('m-1') >> 'fp-a'
        0 * historical._
        0 * store.remember(_, _, _)
        result == CONFLICT
    }

    def "重复上报不写入新记忆"() {
        when:
        3.times { service.decide('m-1', 'fp-a') }

        then:
        3 * store.fingerprintOf('m-1') >> 'fp-a'
        0 * store.remember(_, _, _)
        0 * historical._
    }

    // ── 窗口外兜底 ───────────────────────────────────────────

    def "窗口内命中不查询历史兜底"() {
        when:
        def result = service.decide('m-1', 'fp-a')

        then:
        1 * store.fingerprintOf('m-1') >> 'fp-a'
        0 * historical._
        result == DUPLICATE
    }

    def "窗口外由历史指纹兜底判定为 DUPLICATE"() {
        when:
        def result = service.decide('m-old', 'fp-a')

        then:
        1 * store.fingerprintOf('m-old') >> null
        1 * historical.fingerprintOf('m-old') >> 'fp-a'
        0 * store.remember(_, _, _)
        result == DUPLICATE
    }

    def "窗口外由历史指纹兜底判定为 CONFLICT"() {
        when:
        def result = service.decide('m-old', 'fp-b')

        then:
        1 * store.fingerprintOf('m-old') >> null
        1 * historical.fingerprintOf('m-old') >> 'fp-a'
        0 * store.remember(_, _, _)
        result == CONFLICT
    }

    def "历史兜底也未命中时判定为 NEW"() {
        when:
        def result = service.decide('m-brand-new', 'fp-a')

        then:
        1 * store.fingerprintOf('m-brand-new') >> null
        1 * historical.fingerprintOf('m-brand-new') >> null
        1 * store.remember('m-brand-new', 'fp-a', WINDOW)
        result == NEW
    }

    // ── 指纹计算 ─────────────────────────────────────────────

    def "同一棵树多次计算指纹结果稳定"() {
        given:
        def tree = IngestFixtures.simpleTree("m-1")

        expect:
        calculator.fingerprint(tree) == calculator.fingerprint(tree)
        calculator.fingerprint(tree).length() == 64        // sha256 hex
    }

    def "内容任一处不同则指纹不同"() {
        given: "base 为 POST /orders 耗时 12ms；changed 为 POST /orders 耗时 13ms"
        def base = IngestFixtures.simpleTree("m-1")
        def changedNode = IngestFixtures.treeWith(
                "m-1", "order", "10.0.0.8", "m-1", null, 1790000000000L,
                [IngestFixtures.node("n-1", "URL", "POST /orders", "0", 13L, 1790000000000L)])

        expect:
        calculator.fingerprint(base) != calculator.fingerprint(changedNode)

        and: "节点名称变化同样改变指纹"
        def renamed = IngestFixtures.treeWith(
                "m-1", "order", "10.0.0.8", "m-1", null, 1790000000000L,
                [IngestFixtures.node("n-1", "URL", "POST /pay", "0", 12L, 1790000000000L)])
        calculator.fingerprint(base) != calculator.fingerprint(renamed)
    }

    def "标签 Map 顺序不同但内容相同则指纹相同（规范化）"() {
        given:
        def a = IngestFixtures.metricTree("m-1", [city: "上海", channel: "app"])
        def b = IngestFixtures.metricTree("m-1", [channel: "app", city: "上海"])

        expect:
        calculator.fingerprint(a) == calculator.fingerprint(b)
    }

    def "节点顺序不同则指纹不同（保守判定，避免内容漂移被吞掉）"() {
        given:
        def n1 = IngestFixtures.node("n-1", "URL", "/a", "0", 1L, 1790000000000L)
        def n2 = IngestFixtures.node("n-2", "URL", "/b", "0", 1L, 1790000000000L)
        def forward = IngestFixtures.treeWith("m-1", "order", "10.0.0.8", "m-1", null, 1790000000000L, [n1, n2])
        def reversed = IngestFixtures.treeWith("m-1", "order", "10.0.0.8", "m-1", null, 1790000000000L, [n2, n1])

        expect:
        calculator.fingerprint(forward) != calculator.fingerprint(reversed)
    }

    def "messageId 不同但内容相同，指纹相同（指纹只描述内容）"() {
        given:
        def a = IngestFixtures.simpleTree("m-1")
        def b = IngestFixtures.simpleTree("m-2")

        expect:
        calculator.fingerprint(a) == calculator.fingerprint(b)
    }
}
