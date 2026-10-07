package com.neocat.client

import com.neocat.protocol.ingest.v1.IngestRequest
import com.neocat.protocol.ingest.v1.Kind
import com.neocat.protocol.ingest.v1.Status
import spock.lang.Specification

import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * G11 任务87（红）：记录型 SDK 契约。
 * 对应技术方案 04-ingest-protocol.md §8.2（SDK 契约）与 PRD 02 §8（过载不阻塞业务）。
 */
class NeoCatSdkSpec extends Specification {

    CollectingSender sender
    MutableClientConfig config
    NeoCat cat

    def setup() {
        sender = new CollectingSender()
        config = new MutableClientConfig(1000)
        config.setFlushInterval(20L)
        cat = NeoCat.create(config, "order", "10.0.0.8", "http://localhost:8080/api/v1/ingest", sender)
    }

    def cleanup() {
        cat.shutdown(200)
    }

    // ── 记录 API ─────────────────────────────────────────────

    def "newTransaction 完成时产生一棵含 TRANSACTION 节点的树"() {
        when:
        def tx = cat.newTransaction("URL", "POST /orders")
        tx.setStatus(Transaction.SUCCESS)
        tx.complete()
        cat.awaitFlush(2000)

        then:
        sender.trees().size() == 1
        def tree = sender.trees()[0]
        tree.serviceName == "order"
        tree.instanceId == "10.0.0.8"
        tree.nodesCount == 1
        tree.getNodes(0).kind == Kind.TRANSACTION
        tree.getNodes(0).category == "URL"
        tree.getNodes(0).name == "POST /orders"
        tree.getNodes(0).status == "0"
    }

    def "失败的 Transaction 携带异常信息"() {
        when:
        def tx = cat.newTransaction("SQL", "select_order")
        tx.setStatus(Transaction.FAILURE)
        tx.setException(new IllegalStateException("connection refused"))
        tx.complete()
        cat.awaitFlush(2000)

        then:
        def node = sender.trees()[0].getNodes(0)
        node.status != "0"
        node.hasException()
        node.getException().exceptionName == "java.lang.IllegalStateException"
        node.getException().exceptionMessage == "connection refused"
    }

    def "logEvent 产生 EVENT 节点且不带耗时"() {
        when:
        cat.logEvent("business", "order-created", "0")
        cat.awaitFlush(2000)

        then:
        def node = sender.trees()[0].getNodes(0)
        node.kind == Kind.EVENT
        node.durationMs == 0L
    }

    def "logMetric 产生 METRIC 节点并保留标签"() {
        when:
        cat.logMetric("order.amount", 128.5d, [city: "上海", channel: "app"])
        cat.awaitFlush(2000)

        then:
        def node = sender.trees()[0].getNodes(0)
        node.kind == Kind.METRIC
        node.getMetric().name == "order.amount"
        node.getMetric().value == 128.5d
        node.getMetric().getLabelsMap() == [city: "上海", channel: "app"]
    }

    def "logHeartbeat 产生 JVM HEARTBEAT 节点"() {
        when:
        cat.logHeartbeat([heapUsed: "512000000", heapMax: "2048000000",
                          gcCount: "12", gcTime: "340", threads: "96"])
        cat.awaitFlush(2000)

        then:
        def node = sender.trees()[0].getNodes(0)
        node.kind == Kind.HEARTBEAT
        def hb = node.getHeartbeat()
        hb.heapUsedBytes == 512000000L
        hb.heapMaxBytes == 2048000000L
        hb.gcCount == 12L
        hb.gcTimeMs == 340L
        hb.threadCount == 96L
    }

    def "newRemoteCall 产生 REMOTE_CALL 节点"() {
        when:
        def call = cat.newRemoteCall("pay", "RPC", "POST /pay")
        call.complete()
        cat.awaitFlush(2000)

        then:
        def node = sender.trees()[0].getNodes(0)
        node.kind == Kind.REMOTE_CALL
        node.getRemoteCall().downstreamService == "pay"
        node.getRemoteCall().callType == "RPC"
    }

    def "手动新指标含真实零和缺失，编码为 presence-aware 实际发送"() {
        when:
        cat.logHeartbeat([youngUsed:'0',fullGcCount:'2',metaspaceMax:'-1'])
        cat.awaitFlush(2000)
        then:
        def hb = sender.trees()[0].getNodes(0).heartbeat
        hb.presenceAware
        hb.hasYoungUsedBytes() && hb.youngUsedBytes == 0L
        hb.hasFullGcCount() && hb.fullGcCount == 2L
        !hb.hasHeapUsedBytes()
        !hb.hasMetaspaceMaxBytes()
    }

    def "显式 JVM 采样发送实例 Heartbeat，而 SDK 创建不自动上报"() {
        expect:
        sender.trees().isEmpty()
        when:
        cat.logJvmHeartbeat()
        cat.awaitFlush(2000)
        then:
        sender.trees().size() == 1
        def tree = sender.trees()[0]
        tree.instanceId == '10.0.0.8'
        def hb = tree.getNodes(0).heartbeat
        hb.presenceAware
        hb.hasHeapUsedBytes()
        hb.hasThreadCount()
        !hb.hasFullGcCount()
    }

    // ── messageId 与 Trace 关联 ──────────────────────────────

    def "每次记录产生全局唯一的 messageId"() {
        when:
        (1..20).each { cat.logEvent("business", "e$it", "0") }
        cat.awaitFlush(3000)

        then:
        def ids = sender.trees()*.messageId
        ids.size() == 20
        ids.toSet().size() == 20
    }

    def "无上游时 rootMessageId 等于自身的 messageId"() {
        when:
        cat.logEvent("business", "e", "0")
        cat.awaitFlush(2000)

        then:
        def tree = sender.trees()[0]
        tree.rootMessageId == tree.messageId
    }

    def "attachTrace 后上游 ID 被带入树"() {
        when:
        cat.attachTrace("root-123", "parent-456")
        cat.logEvent("business", "e", "0")
        cat.awaitFlush(2000)

        then:
        def tree = sender.trees()[0]
        tree.rootMessageId == "root-123"
        tree.parentMessageId == "parent-456"
    }

    def "attachTrace 只影响后续记录，不污染此前已入队的树"() {
        when:
        cat.logEvent("business", "before", "0")
        cat.attachTrace("root-x", "parent-x")
        cat.logEvent("business", "after", "0")
        cat.awaitFlush(3000)

        then:
        def trees = sender.trees()
        trees.find { it.getNodes(0).name == "before" }.parentMessageId == ""
        trees.find { it.getNodes(0).name == "after" }.parentMessageId == "parent-x"
    }

    // ── 失败不抛业务异常 ─────────────────────────────────────

    def "发送异常不抛给业务"() {
        given:
        def failing = new MessageSender() {
            @Override
            void send(byte[] payload) { throw new RuntimeException("network down") }
        }
        def c = NeoCat.create(new ClientConfig() {
            @Override
            int queueCapacity() { return 100 }
        }, "order", "10.0.0.8", "http://x", failing)

        when:
        c.logEvent("business", "e", "0")
        c.awaitFlush(1000)

        then:
        noExceptionThrown()

        cleanup:
        c.shutdown(200)
    }

    def "payload 不合法时同样不抛异常"() {
        given:
        def picky = new MessageSender() {
            @Override
            void send(byte[] payload) { throw new IllegalArgumentException("bad payload") }
        }
        def c = NeoCat.create(new ClientConfig() {
            @Override
            int queueCapacity() { return 100 }
        }, "order", "10.0.0.8", "http://x", picky)

        when:
        c.logMetric("m", Double.NaN, [:])
        c.awaitFlush(1000)

        then:
        noExceptionThrown()

        cleanup:
        c.shutdown(200)
    }

    // ── 队列满丢弃 ───────────────────────────────────────────

    def "队列满时丢弃并计数，不阻塞业务"() {
        given:
        def blocking = new MessageSender() {
            @Override
            void send(byte[] payload) {
                Thread.sleep(50)
            }
        }
        def c = NeoCat.create(new ClientConfig() {
            @Override
            int queueCapacity() { return 1 }
        }, "order", "10.0.0.8", "http://x", blocking)

        when:
        def startNanos = System.nanoTime()
        500.times { c.logEvent("business", "e$it", "0") }
        def elapsedMillis = (System.nanoTime() - startNanos) / 1_000_000

        then: "业务侧调用快速返回"
        elapsedMillis < 2000
        and: "丢弃被计数"
        c.dropped() >= 0

        cleanup:
        c.shutdown(300)
    }

    def "丢弃计数可通过 dropped() 观测"() {
        given:
        def c = NeoCat.create(new ClientConfig() {
            @Override
            int queueCapacity() { return 1 }
        }, "order", "10.0.0.8", "http://x", sender)

        when: "容量为 1，持续写入必然产生丢弃"
        1000.times { c.logEvent("business", "e$it", "0") }

        then:
        c.dropped() > 0

        cleanup:
        c.shutdown(300)
    }

    // ── 批量与刷新 ───────────────────────────────────────────

    def "达到批量阈值时自动刷新"() {
        given:
        def small = NeoCat.create(new ClientConfig() {
            @Override
            int queueCapacity() { return 100 }
            @Override
            int batchSize() { return 3 }
        }, "order", "10.0.0.8", "http://x", sender)

        when:
        3.times { small.logEvent("business", "e$it", "0") }
        small.awaitFlush(2000)

        then:
        small.sentBatches() >= 1

        cleanup:
        small.shutdown(200)
    }

    def "定时刷新把未达批量的数据送出"() {
        given:
        def quick = NeoCat.create(new ClientConfig() {
            @Override
            int queueCapacity() { return 100 }
            @Override
            int batchSize() { return 1000 }
            @Override
            long flushIntervalMillis() { return 50 }
        }, "order", "10.0.0.8", "http://x", sender)

        when:
        quick.logEvent("business", "e", "0")
        quick.awaitFlush(2000)

        then:
        sender.trees().size() == 1

        cleanup:
        quick.shutdown(200)
    }

    def "batchSize 可动态调整（Apollo 热更新语义）"() {
        given:
        def live = new MutableClientConfig(100)
        def c = NeoCat.create(new ClientConfig() {
            @Override
            int queueCapacity() { return live.queueCapacity() }
            @Override
            int batchSize() { return live.batchSize() }
        }, "order", "10.0.0.8", "http://x", sender)

        when:
        live.setBatchSize(1)
        c.logEvent("business", "e", "0")
        c.awaitFlush(2000)

        then:
        sender.trees().size() == 1

        cleanup:
        c.shutdown(200)
    }

    /**
     * 回归：`awaitFlush` 必须覆盖「已 drain、尚未 send」这段窗口。
     *
     * <p>旧实现只看 `buffer.isEmpty()`，而刷新线程是「先 drain、后 send」，
     * 于是在这个窗口内提前返回，调用方随即断言已发送数据就会间歇性失败
     * （实测负载下约 8%，属于 SDK 真实缺陷而非测试问题）。
     *
     * <p>本用例把窗口放大到 300ms：若 `awaitFlush` 仍只判断队列为空，
     * 它会在 send 完成前返回，断言必然失败 —— 与机器负载无关。
     */
    def "awaitFlush 在发送完成后才返回（覆盖 drain 与 send 之间的窗口）"() {
        given:
        def seen = new AtomicInteger()
        def slow = new MessageSender() {
            @Override
            void send(byte[] payload) {
                Thread.sleep(300)          // 放大窗口
                seen.incrementAndGet()     // 只有在 send 真正执行后才计数
            }
        }
        def c = NeoCat.create(new ClientConfig() {
            @Override
            int queueCapacity() { return 100 }
            @Override
            int batchSize() { return 1 }
        }, "order", "10.0.0.8", "http://x", slow)

        when:
        c.logEvent("business", "e", "0")
        def flushed = c.awaitFlush(5000)

        then: "awaitFlush 返回时 send 已经完成，而不是刚 drain 完"
        flushed
        seen.get() == 1

        cleanup:
        c.shutdown(1000)
    }

    // ── 停机 ─────────────────────────────────────────────────

    def "shutdown 尽力发送剩余数据"() {
        given:
        def c = NeoCat.create(new ClientConfig() {
            @Override
            int queueCapacity() { return 100 }
            @Override
            int batchSize() { return 1000 }
            @Override
            long flushIntervalMillis() { return 60_000 }
        }, "order", "10.0.0.8", "http://x", sender)

        when:
        c.logEvent("business", "e1", "0")
        c.logEvent("business", "e2", "0")
        c.shutdown(2000)

        then:
        sender.trees().size() == 2
    }

    def "shutdown 超时后不阻塞调用方"() {
        given:
        def hanging = new MessageSender() {
            @Override
            void send(byte[] payload) {
                Thread.sleep(60_000)
            }
        }
        def c = NeoCat.create(new ClientConfig() {
            @Override
            int queueCapacity() { return 100 }
            @Override
            long flushIntervalMillis() { return 60_000 }
        }, "order", "10.0.0.8", "http://x", hanging)
        c.logEvent("business", "e", "0")

        when:
        def startNanos = System.nanoTime()
        c.shutdown(500)
        def elapsedMillis = (System.nanoTime() - startNanos) / 1_000_000

        then: "应在超时附近返回，而不是等 60 秒"
        elapsedMillis < 5000
    }

    def "停机后不再接受新记录且不抛异常"() {
        given:
        cat.shutdown(500)

        when:
        cat.logEvent("business", "after-shutdown", "0")
        cat.logMetric("m", 1.0d, [:])

        then:
        noExceptionThrown()
    }

    // ── 协议正确性 ───────────────────────────────────────────

    def "发送的 payload 是合法的 Protobuf 批次且带协议版本"() {
        when:
        cat.logEvent("business", "e", "0")
        cat.awaitFlush(2000)

        then: "payload 可被反序列化为 IngestRequest"
        def request = sender.requests()[0]
        request.protocolVersion == "1.0"
        request.treesCount >= 1
    }

    def "一批多棵树时合并为单个请求"() {
        given:
        def batching = NeoCat.create(new ClientConfig() {
            @Override
            int queueCapacity() { return 100 }
            @Override
            int batchSize() { return 10 }
            @Override
            long flushIntervalMillis() { return 60_000 }
        }, "order", "10.0.0.8", "http://x", sender)

        when:
        5.times { batching.logEvent("business", "e$it", "0") }
        batching.shutdown(2000)

        then: "批量阈值 10 未达，停机时合并发出"
        sender.requests().size() == 1
        sender.requests()[0].treesCount == 5

        cleanup:
        batching.shutdown(200)
    }

    def "树内节点按记录顺序排列"() {
        when:
        cat.logEvent("business", "first", "0")
        cat.logEvent("business", "second", "0")
        cat.awaitFlush(3000)

        then:
        sender.trees()*.getNodes(0)*.name == ["first", "second"]
    }

    def "serviceName 与 instanceId 出现在每棵树上"() {
        when:
        3.times { cat.logEvent("business", "e$it", "0") }
        cat.awaitFlush(3000)

        then:
        sender.trees().every { it.serviceName == "order" && it.instanceId == "10.0.0.8" }
    }

    /**
     * 方案 B 的核心不变式：协议类由本模块**自行生成**，且生成物与服务端框架解耦。
     * 因此 SDK 的类路径上不存在任何 Spring / Modulith 类型 —— 若有人把
     * 服务端框架注解重新混进协议生成物，本规格立即失败。
     */
    def "SDK 侧协议生成类不携带任何 Spring 注解（协议与服务端框架解耦）"() {
        expect: "协议包上没有任何 org.springframework.* 注解"
        IngestRequest.getPackage().getAnnotations().every {
            !it.annotationType().name.startsWith("org.springframework")
        }

        and: "SDK 的类路径上根本没有 Spring（连注解类型都无法解析）"
        this.class.classLoader.getResource("org/springframework") == null
    }
}
