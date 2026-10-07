package com.neocat.ingest.domain.receive

import com.neocat.ingest.domain.idempotency.HistoricalFingerprintLookup
import com.neocat.ingest.domain.idempotency.IdempotencyService
import com.neocat.ingest.domain.tree.IngestFixtures
import com.neocat.ingest.domain.tree.MessageTree
import com.neocat.ingest.domain.validation.FingerprintCalculator
import com.neocat.ingest.domain.validation.LatenessPolicy
import com.neocat.ingest.domain.validation.TreeValidator

import com.neocat.common.queue.BoundedDropQueue
import com.neocat.common.queue.impl.BoundedDropQueueFactory
import spock.lang.Specification

import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime

import static com.neocat.ingest.domain.receive.IngestStatus.*
import static com.neocat.ingest.domain.receive.QualityType.*

/**
 * G5 任务27（红）：过载丢弃与接收编排。
 * 对应 PRD 02 §4（接收流程）、§5（发现先于入队）、§8（有界队列不阻塞）、§11（验收）。
 */
class IngestOverloadSpec extends Specification {

    static final ZoneId SH = ZoneId.of("Asia/Shanghai")
    static final Instant NOW = ZonedDateTime.of(2026, 9, 24, 12, 23, 41, 0, SH).toInstant()
    static final long NOW_MS = NOW.toEpochMilli()

    CatalogGateway catalog
    QualityEventSink quality
    List<QualityType> qualityTypes
    BoundedDropQueue<MessageTree> queue
    IngestService service

    def setup() {
        catalog = Mock(CatalogGateway)
        qualityTypes = []
        quality = Mock(QualityEventSink) {
            record(_, _, _, _, _) >> { QualityType type, String service,
                                       String messageId, String detail, Instant at -> qualityTypes.add(type) }
        }
        queue = new BoundedDropQueueFactory().create(4)
        service = new IngestService(
                new TreeValidator(),
                new LatenessPolicy(),
                new IdempotencyService(new com.neocat.ingest.infra.InMemoryIdempotencyStore(),
                         Stub(HistoricalFingerprintLookup) {
                             fingerprintOf(_ as String) >> null
                          }),
                new FingerprintCalculator(),
                catalog, queue, quality,
                { NOW } as com.neocat.common.time.clock.ClockProvider,
                { SH } as java.util.function.Supplier<ZoneId>)
    }

    static IngestBatch batch(MessageTree... trees) {
        new IngestBatch("1.0", trees.toList())
    }

    static MessageTree tree(String messageId = "m-1", long treeTimestamp = NOW_MS) {
        IngestFixtures.treeWith(messageId, "order", "10.0.0.8", messageId, null, treeTimestamp,
                [IngestFixtures.node("n-1", "URL", "POST /orders", "0", 12L, treeTimestamp)])
    }

    // ── 正常接收 ─────────────────────────────────────────────

    def "合法上报返回 ACCEPTED 并入队"() {
        when:
        def result = service.accept(batch(tree()), 100)

        then:
        result.getStatus() == ACCEPTED
        result.getAcceptedTrees() == 1
        queue.size() == 1
    }

    def "接收不消耗队列：分析由后台消费者驱动，接收端立即返回（PRD 02 §8）"() {
        when:
        service.accept(batch(tree("m-1"), tree("m-2")), 200)

        then: "两棵树仍在队列中等待消费者，接收阶段未做任何分析处理"
        queue.size() == 2
    }

    def "多棵树批量接收"() {
        when:
        def result = service.accept(batch(tree("m-1"), tree("m-2"), tree("m-3")), 300)

        then:
        result.getStatus() == ACCEPTED
        result.getAcceptedTrees() == 3
        queue.size() == 3
    }

    // ── 校验与版本 ───────────────────────────────────────────

    def "不支持的协议版本整批拒绝"() {
        when:
        def result = service.accept(new IngestBatch("2.0", [tree()]), 100)

        then:
        result.getStatus() == REJECTED
        result.getCode() == "UNSUPPORTED_VERSION"
        queue.size() == 0
    }

    def "非法树被拒绝且不入队"() {
        given:
        def broken = IngestFixtures.treeWith(null, "order", "10.0.0.8", "m-1", null, NOW_MS,
                [IngestFixtures.node("n-1", "URL", "/a", "0", 1L, NOW_MS)])

        when:
        def result = service.accept(batch(broken), 100)

        then:
        result.getStatus() == REJECTED
        result.getCode() == "MALFORMED_TREE"
        queue.size() == 0
        qualityTypes == [MALFORMED] as List
    }

    // ── 迟到 ─────────────────────────────────────────────────

    def "过期树整棵拒绝并写质量事件"() {
        given: "上上小时的数据"
        def stale = NOW_MS - Duration.ofHours(2).toMillis()

        when:
        def result = service.accept(batch(tree("m-old", stale)), 100)

        then:
        result.getStatus() == REJECTED
        result.getCode() == "TREE_EXPIRED"
        queue.size() == 0
        qualityTypes == [EXPIRED] as List
    }

    def "过期树不进入任何报表：不入队即不参与分析"() {
        given:
        def stale = NOW_MS - Duration.ofHours(5).toMillis()

        when:
        service.accept(batch(tree("m-old", stale)), 100)

        then:
        queue.size() == 0
    }

    def "上一小时的数据可接收"() {
        given:
        def previousHour = NOW_MS - Duration.ofHours(1).toMillis()

        when:
        def result = service.accept(batch(tree("m-prev", previousHour)), 100)

        then:
        result.getStatus() == ACCEPTED
    }

    // ── 幂等 ─────────────────────────────────────────────────

    def "相同树重试返回 DUPLICATE 且不重复入队"() {
        given:
        def first = service.accept(batch(tree()), 100)

        when:
        def second = service.accept(batch(tree()), 100)

        then:
        first.getStatus() == ACCEPTED
        second.getStatus() == DUPLICATE
        second.getDuplicateTrees() == 1
        queue.size() == 1
    }

    def "相同 ID 不同内容被拒绝并记录 ID 冲突"() {
        given:
        service.accept(batch(tree()), 100)
        def conflicting = IngestFixtures.treeWith("m-1", "order", "10.0.0.8", "m-1", null, NOW_MS,
                [IngestFixtures.node("n-1", "URL", "POST /orders", "0", 999L, NOW_MS)])

        when:
        def result = service.accept(batch(conflicting), 100)

        then:
        result.getStatus() == REJECTED
        result.getCode() == "ID_CONFLICT"
        queue.size() == 1
        qualityTypes == [ID_CONFLICT] as List
    }

    // ── 发现先于入队 ─────────────────────────────────────────

    def "合法上报触发服务与实例自动发现"() {
        when:
        service.accept(batch(tree()), 100)

        then:
        1 * catalog.discover('order', '10.0.0.8', _ as Instant)
    }

    def "队列满丢弃时服务仍已被发现（关键顺序不变式）"() {
        given: "先把队列填满"
        (1..4).each { queue.offer(tree("filler-$it")) }

        when: "队列已满，新树入队会失败"
        def result = service.accept(batch(tree("m-new")), 100)

        then: "结果是丢弃"
        result.getStatus() == DROPPED
        result.getCode() == "QUEUE_FULL"
        result.getDroppedTrees() == 1
        qualityTypes == [QUEUE_FULL] as List

        and: "但服务与实例已经在目录中被发现"
        1 * catalog.discover('order', '10.0.0.8', _ as Instant)
    }

    def "丢弃的树不进入队列，队列内容不变"() {
        given:
        (1..4).each { queue.offer(tree("filler-$it")) }

        when:
        service.accept(batch(tree("m-new")), 100)

        then:
        queue.size() == 4
    }

    def "队列满不阻塞：接收耗时为毫秒级"() {
        given:
        (1..4).each { queue.offer(tree("filler-$it")) }

        when:
        def startNanos = System.nanoTime()
        200.times { service.accept(batch(tree("m-$it")), 100) }
        def elapsedMillis = (System.nanoTime() - startNanos) / 1_000_000

        then:
        elapsedMillis < 2000
    }

    def "丢弃不补算：质量事件记录但无重试"() {
        given:
        (1..4).each { queue.offer(tree("filler-$it")) }

        when:
        service.accept(batch(tree("m-1")), 100)

        then: "只有一次记录，没有自动重试"
        qualityTypes.count(QUEUE_FULL) == 1
        1 * catalog.discover('order', '10.0.0.8', _ as Instant)
    }

    def "混合批次：合法、重复、过期分别计数"() {
        given:
        service.accept(batch(tree("m-1")), 100)          // 先占一个位置并建立幂等
        def stale = tree("m-old", NOW_MS - Duration.ofHours(3).toMillis())

        when:
        def result = service.accept(batch(tree("m-1"), tree("m-2"), stale), 300)

        then:
        result.getDuplicateTrees() == 1
        result.getAcceptedTrees() == 1
        result.getRejectedTrees() == 1
        result.getStatus() == ACCEPTED
    }

    def "空批次被拒"() {
        when:
        def result = service.accept(new IngestBatch("1.0", []), 0)

        then:
        result.getStatus() == REJECTED
        result.getCode() == "BATCH_TOO_LARGE"
    }
}
