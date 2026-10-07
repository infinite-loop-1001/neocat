package com.neocat.analysis.infra

import com.neocat.analysis.domain.analyzer.*
import com.neocat.analysis.domain.bucket.*
import com.neocat.analysis.domain.dependency.DependencyAnalyzer
import com.neocat.analysis.domain.metric.*
import com.neocat.analysis.domain.schedule.ReportScheduler
import com.neocat.analysis.infra.store.*
import com.neocat.analysis.infra.job.RealtimeConsumerLoop
import com.neocat.common.config.*
import com.neocat.common.queue.BoundedDropQueue
import org.springframework.context.annotation.AnnotationConfigApplicationContext
import spock.lang.Specification
import java.time.ZoneOffset
import java.util.function.Supplier

/** 使用真实组件构造函数与 Spring 容器，外部边界只有 Stub。 */
class AnalysisComponentWiringSpec extends Specification {
    def "六个分析器自动注入且循环在应用就绪前不启动"() {
        given:
        def queue = Stub(BoundedDropQueue)
        def sink = Stub(ReportBucketSinkPort)
        def threshold = Stub(SlowThresholdProvider) {
            urlMs() >> 1000
            sqlMs() >> 100
            callMs() >> 1000
            cacheMs() >> 50
        }
        def context = new AnnotationConfigApplicationContext()
        context.registerBean(BoundedDropQueue, { queue } as Supplier)
        context.registerBean(ReportBucketSinkPort, { sink } as Supplier)
        context.registerBean(SlowThresholdProvider, { threshold } as Supplier)
        context.registerBean(Supplier, { { ZoneOffset.UTC } as Supplier } as Supplier)
        context.register(IngestConfig, ReportConfig, MetricConfig,
                InMemoryHourlyReportStore, InMemoryMetricHourRank, InMemoryMetricLabelMetadata,
                MinuteBucketReader, ReportScheduler, TransactionAnalyzer, EventAnalyzer,
                ProblemAnalyzer, HeartbeatAnalyzer, MetricAnalyzer, DependencyAnalyzer,
                RealtimeConsumer, RealtimeConsumerLoop)

        when:
        context.refresh()

        then:
        context.getBean(RealtimeConsumer).analyzers()*.domain().toSet() ==
                ['transaction', 'event', 'problem', 'heartbeat', 'metric', 'dependency'] as Set
        context.getBean(HourlyReportStore).is(context.getBean(InMemoryHourlyReportStore))
        context.getBean(MinuteBucketSource).is(context.getBean(MinuteBucketReader))
        context.getBean(MetricHourRank).is(context.getBean(InMemoryMetricHourRank))
        context.getBean(ReportScheduler) != null
        !context.getBean(RealtimeConsumerLoop).running
        !context.getBean(RealtimeConsumerLoop).autoStartup

        cleanup:
        context.close()
    }
}
