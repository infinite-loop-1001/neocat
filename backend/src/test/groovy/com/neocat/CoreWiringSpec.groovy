package com.neocat

import com.neocat.analysis.domain.analyzer.Analyzer
import com.neocat.analysis.domain.analyzer.RealtimeConsumer
import com.neocat.common.queue.BoundedDropQueue
import com.neocat.ingest.domain.receive.IngestService
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.context.ApplicationContext
import org.springframework.test.context.ContextConfiguration
import spock.lang.Specification

/**
 * 阶段 3 收尾（红）：核心运行时装配自检。
 *
 * <p>验证「上报接收链路 + 分析扇出链路」的 Bean 依赖关系完整、可启动。
 * **不连接任何外部中间件**（MySQL / ClickHouse / Apollo），符合项目纪律。
 *
 * <p>价值：领域类全部是无框架依赖的 POJO，装配关系若有误会在此暴露。
 * 这也是「后端可运行」的第一层证据；真实端口联调由人工接通中间件后执行。
 */
@ContextConfiguration(classes = CoreWiringConfiguration)
class CoreWiringSpec extends Specification {

    @Autowired
    IngestService ingestService

    @Autowired
    BoundedDropQueue ingestQueue

    @Autowired
    RealtimeConsumer realtimeConsumer

    @Autowired
    List<Analyzer> analyzers

    @Autowired
    ApplicationContext context

    def "Spring 上下文可启动且上报接收服务已装配"() {
        expect:
        ingestService != null
    }

    def "有界队列容量来自运行参数默认值"() {
        expect:
        ingestQueue.capacity() == 65536
        ingestQueue.size() == 0
        ingestQueue.droppedCount() == 0
    }

    def "接收服务暴露队列观测数据（容量 / 水位 / 累计丢弃）"() {
        when:
        def stats = ingestService.queueStats()

        then:
        stats.getCapacity() == 65536
        stats.getSize() == 0
        stats.getDroppedTotal() == 0
        stats.getWatermark() == 0.0d
    }

    def "六个分析器全部装配且域名唯一"() {
        expect:
        analyzers.size() == 6
        analyzers*.domain() as Set ==
                ["transaction", "event", "problem", "heartbeat", "metric", "dependency"] as Set
    }

    def "扇出调度持有全部分析器"() {
        expect:
        realtimeConsumer.analyzers().size() == 6
    }

    def "扇出对无节点树不报错（单域异常被隔离）"() {
        given:
        def tree = new com.neocat.ingest.domain.tree.MessageTree(
                "order", "10.0.0.8", "m-1", "m-1", null, 1_790_000_000_000L, List.of())

        when:
        def result = realtimeConsumer.consume(tree)

        then:
        noExceptionThrown()
        !result.anyFailed()
    }

    def "装配后一次真实扇出：全部分析域成功处理"() {
        given:
        def node = new com.neocat.ingest.domain.tree.RawNode("n-1",
                com.neocat.ingest.domain.tree.NodeKind.TRANSACTION, "URL", "POST /orders", "0",
                1_790_000_000_000L, 12L, null, null, null, null, null, Map.of())
        def tree = new com.neocat.ingest.domain.tree.MessageTree(
                "order", "10.0.0.8", "m-1", "m-1", null, 1_790_000_000_000L, List.of(node))

        when:
        def result = realtimeConsumer.consume(tree)

        then: "各域自行忽略不相关的节点类型，都不失败"
        result.getFailures().isEmpty()
        result.getSucceededDomains().size() == 6
    }

    def "报表滚动链路已装配：读取器与调度器就位"() {
        expect:
        context.getBean(com.neocat.analysis.infra.store.MinuteBucketReader) != null
        context.getBean(com.neocat.analysis.domain.schedule.ReportScheduler) != null
        context.getBean(com.neocat.analysis.domain.bucket.ReportBucketSinkPort) != null
    }

    def "调度器可对空报表执行一次完整滚动且不报错"() {
        given:
        def scheduler = context.getBean(com.neocat.analysis.domain.schedule.ReportScheduler)
        def now = java.time.Instant.now()

        expect: "无数据时各层级都返回 0，且不抛异常"
        scheduler.flushCompletedMinute(now) == 0
        scheduler.rollupCompletedHour(now) == 0
        scheduler.rollupCompletedDay(now) == 0
        scheduler.rollupCompletedWeek(now) == 0
        scheduler.rollupCompletedMonth(now) == 0
    }

    def "装配后的接收链路可用：合法批次被接受并入队"() {
        given:
        def node = new com.neocat.ingest.domain.tree.RawNode("n-1",
                com.neocat.ingest.domain.tree.NodeKind.TRANSACTION, "URL", "/a", "0",
                1_790_000_000_000L, 1L, null, null, null, null, null, Map.of())
        def tree = new com.neocat.ingest.domain.tree.MessageTree(
                "order", "10.0.0.8", "m-wiring", "m-wiring", null, 1_790_000_000_000L, List.of(node))
        def batch = new com.neocat.ingest.domain.receive.IngestBatch("1.0", List.of(tree))

        when:
        def result = ingestService.accept(batch, 100)

        then: "装配后的依赖链能正常协作"
        noExceptionThrown()
        result.totalTrees() == 1
    }
}
