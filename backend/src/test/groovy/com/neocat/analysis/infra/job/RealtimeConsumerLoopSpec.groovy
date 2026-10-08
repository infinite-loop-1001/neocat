package com.neocat.analysis.infra.job

import com.neocat.analysis.domain.analyzer.Analyzer
import com.neocat.analysis.domain.analyzer.RealtimeConsumer
import com.neocat.ingest.config.IngestConfig
import com.neocat.common.queue.impl.BoundedDropQueueFactory
import com.neocat.ingest.domain.tree.MessageTree
import spock.lang.Specification

import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import com.neocat.common.queue.BoundedDropQueue
import com.neocat.ingest.domain.tree.NodeKind
import com.neocat.ingest.domain.tree.RawNode
import spock.util.concurrent.PollingConditions

/**
 * 有界队列消费循环的规格（PRD 02 §8、§9）。
 *
 * <p>验证三件事：
 * 1. 入队的树确实被消费并扇出到全部分析域；
 * 2. 分析域抛异常时循环**不退出**，其余树继续被消费；
 * 3. 停机后不再消费。
 *
 * <p>这些性质直接对应 PRD 02 §8「接收端不得等待分析器处理完成」
 * 与 §9「一个处理域失败时其他处理域继续处理同一树」。
 */
class RealtimeConsumerLoopSpec extends Specification {

    static final long T = 1_790_000_000_000L

    def queue = new BoundedDropQueueFactory().create(100)
    def consumed = new CopyOnWriteArrayList<String>()
    def failures = new AtomicInteger()
    def latch = new CountDownLatch(1)

    def setup() {
        IngestConfig.CONSUMER_THREADS = 1
        IngestConfig.BATCH_SIZE = 10
        IngestConfig.BATCH_TIMEOUT_MS = 20
    }

    static MessageTree tree(String messageId) {
        def node = new RawNode("n-1",
                NodeKind.TRANSACTION, "URL", "/a", "0", T, 1L,
                null, null, null, null, null, Map.of())
        return new MessageTree("order", "10.0.0.8", messageId, messageId, null, T, [node])
    }

    /** 记录型分析器：可选在指定 messageId 上抛异常。 */
    def analyzer(String domain, String poisonMessageId = null) {
        return new Analyzer() {
            @Override
            String domain() {
                return domain
            }

            @Override
            void analyze(MessageTree t) {
                if (poisonMessageId != null && poisonMessageId == t.getMessageId()) {
                    throw new IllegalStateException("域 $domain 处理失败")
                }
                // 显式 toString：Groovy 的 GString 存入 List<String> 后
                // equals(String) 为 false，会让断言出现「内容相同却不相等」的假失败
                consumed << ("$domain:${t.getMessageId()}".toString())
                latch.countDown()
            }
        }
    }

    def cleanup() {
        loop?.stop()
    }

    /** 轮询等待某个条目出现，避免依赖单次 latch 的时序。 */
    boolean awaitConsumed(String entry, long timeoutMillis = 3000) {
        long deadline = System.currentTimeMillis() + timeoutMillis
        while (System.currentTimeMillis() < deadline) {
            if (consumed.contains(entry)) return true
            Thread.sleep(10)
        }
        return consumed.contains(entry)
    }

    RealtimeConsumerLoop loop

    def "同一消费循环在更新后使用新批大小与超时，而非循环外快照"() {
        given:
        def calls = new CopyOnWriteArrayList<List>()
        def dynamicQueue = new BoundedDropQueue<MessageTree>() {
            boolean offer(MessageTree item) { true }
            List<MessageTree> pollBatch(int maxItems, long timeoutMillis) {
                calls.add([maxItems, timeoutMillis])
                Thread.sleep(5)
                return []
            }
            long droppedCount() { 0 }
            int size() { 0 }
            int capacity() { 100 }
            double watermark() { 0 }
        }
        loop = new RealtimeConsumerLoop(dynamicQueue, new RealtimeConsumer([]))
        loop.start()

        when:
        new PollingConditions(timeout: 3).eventually { assert calls.contains([10, 20L]) }
        IngestConfig.BATCH_SIZE = 3
        IngestConfig.BATCH_TIMEOUT_MS = 7

        then:
        new PollingConditions(timeout: 3).eventually { assert calls.contains([3, 7L]) }
    }

    def "线程数更新后扩容和缩容，维护已启动 worker 而非配置副本"() {
        given:
        loop = new RealtimeConsumerLoop(queue, new RealtimeConsumer([]))
        loop.start()

        when:
        IngestConfig.CONSUMER_THREADS = 3
        loop.maintainConsumers()

        then:
        new PollingConditions(timeout: 3).eventually { assert loop.activeWorkerCount() == 3 }

        when:
        IngestConfig.CONSUMER_THREADS = 1

        then:
        new PollingConditions(timeout: 3).eventually { assert loop.activeWorkerCount() == 1 }
    }

    // ── 正常消费 ─────────────────────────────────────────────

    def "入队的树被消费并扇出到全部分析域"() {
        given:
        loop = new RealtimeConsumerLoop(queue,
                new RealtimeConsumer([analyzer("transaction"), analyzer("event")]))

        when:
        loop.start()
        queue.offer(tree("m-1"))

        then:
        awaitConsumed("transaction:m-1")
        awaitConsumed("event:m-1")
        loop.consumedCount() >= 1
    }

    def "一次入队多棵树时全部被消费"() {
        given:
        def counter = new CountDownLatch(3)
        def counting = new Analyzer() {
            @Override
            String domain() {
                return "transaction"
            }

            @Override
            void analyze(MessageTree t) {
                counter.countDown()
            }
        }
        loop = new RealtimeConsumerLoop(queue, new RealtimeConsumer([counting]))

        when:
        loop.start()
        (1..3).each { queue.offer(tree("m-$it")) }
        def drained = counter.await(3, TimeUnit.SECONDS)

        then:
        drained
    }

    // ── 异常隔离 ─────────────────────────────────────────────

    def "分析域抛异常时循环不退出：后续树仍被消费"() {
        given: "transaction 域在 m-poison 上抛异常"
        loop = new RealtimeConsumerLoop(queue,
                new RealtimeConsumer([analyzer("transaction", "m-poison"), analyzer("event")]))

        when:
        loop.start()
        queue.offer(tree("m-poison"))
        queue.offer(tree("m-after"))

        then: "后续树仍被处理"
        awaitConsumed("event:m-after")
        and: "失败被计数而非吞掉"
        loop.failedCount() >= 1
    }

    def "全部分析域都失败时循环仍存活"() {
        given:
        loop = new RealtimeConsumerLoop(queue,
                new RealtimeConsumer([analyzer("a", "m-1"), analyzer("b", "m-1")]))

        when:
        loop.start()
        queue.offer(tree("m-1"))
        queue.offer(tree("m-2"))
        Thread.sleep(300)

        then:
        noExceptionThrown()
        loop.consumedCount() >= 1
    }

    // ── 停机 ─────────────────────────────────────────────────

    def "停机后不再消费新入队的树"() {
        given:
        loop = new RealtimeConsumerLoop(queue, new RealtimeConsumer([analyzer("transaction")]))
        loop.start()

        when:
        loop.stop()
        def before = loop.consumedCount()
        queue.offer(tree("m-late"))
        Thread.sleep(150)

        then:
        loop.consumedCount() == before
        !loop.isRunning()
    }

    def "重复 start 只启动一次（幂等）"() {
        given:
        loop = new RealtimeConsumerLoop(queue, new RealtimeConsumer([analyzer("transaction")]))

        when:
        loop.start()
        loop.start()
        queue.offer(tree("m-1"))
        awaitConsumed("transaction:m-1")
        Thread.sleep(100)

        then: "同一棵树只被消费一次"
        consumed.count { it == "transaction:m-1" } == 1
    }

    def "停机是可重入的，不抛异常"() {
        given:
        loop = new RealtimeConsumerLoop(queue, new RealtimeConsumer([analyzer("transaction")]))
        loop.start()

        when:
        loop.stop()
        loop.stop()

        then:
        noExceptionThrown()
    }

    // ── 与接收侧的隔离 ───────────────────────────────────────

    def "消费是异步的：入队后立即返回，不等待消费完成"() {
        given: "一个很慢的分析器"
        def slow = new Analyzer() {
            @Override
            String domain() {
                return "transaction"
            }

            @Override
            void analyze(MessageTree t) {
                Thread.sleep(500)
            }
        }
        loop = new RealtimeConsumerLoop(queue, new RealtimeConsumer([slow]))

        when:
        loop.start()
        def startNanos = System.nanoTime()
        queue.offer(tree("m-1"))
        def elapsedMillis = (System.nanoTime() - startNanos) / 1_000_000

        then: "入队只在毫秒级返回，与消费耗时无关"
        elapsedMillis < 100
    }
}
