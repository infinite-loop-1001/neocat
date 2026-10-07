package com.neocat;

import com.neocat.analysis.infra.store.InMemoryHourlyReportStore;
import com.neocat.analysis.infra.store.InMemoryMetricHourRank;
import com.neocat.analysis.domain.analyzer.Analyzer;
import com.neocat.analysis.domain.dependency.DependencyAnalyzer;
import com.neocat.analysis.domain.analyzer.EventAnalyzer;
import com.neocat.analysis.domain.analyzer.HeartbeatAnalyzer;
import com.neocat.analysis.domain.bucket.HourlyReportStore;
import com.neocat.analysis.domain.analyzer.MetricAnalyzer;
import com.neocat.analysis.domain.metric.MetricHourRank;
import com.neocat.analysis.domain.analyzer.ProblemAnalyzer;
import com.neocat.analysis.domain.analyzer.RealtimeConsumer;
import com.neocat.analysis.domain.analyzer.SlowThresholdProvider;
import com.neocat.analysis.domain.analyzer.TransactionAnalyzer;
import com.neocat.common.config.IngestConfig;
import com.neocat.common.queue.BoundedDropQueue;
import com.neocat.common.queue.QueueFactory;
import com.neocat.common.queue.impl.BoundedDropQueueFactory;
import com.neocat.common.time.clock.ClockProvider;
import com.neocat.ingest.infra.InMemoryIdempotencyStore;
import com.neocat.ingest.domain.receive.CatalogGateway;
import com.neocat.ingest.domain.validation.FingerprintCalculator;
import com.neocat.ingest.domain.idempotency.HistoricalFingerprintLookup;
import com.neocat.ingest.domain.idempotency.IdempotencyService;
import com.neocat.ingest.domain.idempotency.IdempotencyStore;
import com.neocat.ingest.domain.receive.IngestService;
import com.neocat.ingest.domain.validation.LatenessPolicy;
import com.neocat.ingest.domain.tree.MessageTree;
import com.neocat.ingest.domain.receive.QualityEventSink;
import com.neocat.ingest.domain.validation.TreeValidator;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;
import java.time.Duration;
import java.time.ZoneId;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

/**
 * 测试装配：只装配**不依赖任何外部中间件**的领域链路。
 *
 * <p>覆盖范围：上报接收链路（校验 → 迟到 → 幂等 → 有界队列）与分析扇出链路
 * （6 个分析器 + RealtimeConsumer）。这两条链路是 NeoCat 的核心运行时，
 * 且它们的依赖全部可以通过内存替身提供。
 *
 * <p>不在此处装配的部分（需要 MySQL / ClickHouse / Apollo）：
 * 账号与会话仓储、目录仓储、大盘仓储、Trace 存储、报表查询仓储。
 * 这些边界由 {@code infra} 层适配器在接通中间件后提供，
 * 其单元语义已在各自的领域规格中覆盖。
 */
@Configuration
public class CoreWiringConfiguration {

    /** 离线测试入口，配置值只写入静态字段，不创建运行参数 Bean。 */
    @Bean
    public Object testConfigReady(
            @Value("${neocat.ingest.queue.capacity:65536}") String queueCapacity,
            @Value("${neocat.ingest.idempotency-window-minutes:120}") String idempotencyWindow,
            @Value("${neocat.ingest.accept-late-hours:2}") String acceptLateHours) {
        StaticConfigFixture.defaults();
        StaticConfigFixture.overrides(java.util.Map.of(
                "neocat.ingest.queue.capacity", queueCapacity,
                "neocat.ingest.idempotency-window-minutes", idempotencyWindow,
                "neocat.ingest.accept-late-hours", acceptLateHours));
        return new Object();
    }

    @Bean
    public Clock clock() {
        return Clock.systemUTC();
    }

    @Bean
    public QueueFactory queueFactory() {
        return new BoundedDropQueueFactory();
    }

    @Bean
    @org.springframework.context.annotation.DependsOn("testConfigReady")
    public BoundedDropQueue<MessageTree> ingestQueue(QueueFactory factory) {
        return new com.neocat.ingest.infra.IngestDropQueue<>();
    }

    @Bean
    public ClockProvider clockProvider(Clock clock) {
        return clock::instant;
    }

    @Bean
    public Supplier<ZoneId> platformZone() {
        return () -> ZoneId.of("Asia/Shanghai");
    }

    @Bean
    public TreeValidator treeValidator() {
        return new TreeValidator();
    }

    @Bean
    public LatenessPolicy latenessPolicy() {
        return new LatenessPolicy();
    }

    @Bean
    public IdempotencyStore idempotencyStore() {
        return new InMemoryIdempotencyStore();
    }

    @Bean
    public IdempotencyService idempotencyService(IdempotencyStore store) {
        return new IdempotencyService(store, HistoricalFingerprintLookup.empty());
    }

    @Bean
    public FingerprintCalculator fingerprintCalculator() {
        return new FingerprintCalculator();
    }

    @Bean
    public CatalogGateway catalogGateway() {
        // 内存替身：接收链路只要求「发现先于入队」被调用，不要求持久化
        return (serviceName, instanceId, at) -> {
        };
    }

    @Bean
    public QualityEventSink qualityEventSink() {
        return QualityEventSink.noop();
    }

    @Bean
    public IngestService ingestService(TreeValidator validator, LatenessPolicy lateness,
                                       IdempotencyService idempotency, FingerprintCalculator fingerprints,
                                       CatalogGateway catalogGateway,
                                       BoundedDropQueue<MessageTree> ingestQueue,
                                       QualityEventSink qualityEventSink, ClockProvider clockProvider,
                                       Supplier<ZoneId> platformZone) {
        return new IngestService(validator, lateness, idempotency, fingerprints,
                catalogGateway, ingestQueue, qualityEventSink, clockProvider, platformZone);
    }

    // ── 分析链路 ─────────────────────────────────────────────

    @Bean
    public HourlyReportStore hourlyReportStore() {
        return new InMemoryHourlyReportStore();
    }

    @Bean
    public MetricHourRank metricHourRank() {
        return new InMemoryMetricHourRank();
    }

    /** 慢阈值：与平台初始化默认值一致（URL 1000 / SQL 100 / 调用 1000 / 缓存 50）。 */
    @Bean
    public SlowThresholdProvider slowThresholdProvider() {
        return new SlowThresholdProvider() {
            @Override
            public int urlMs() {
                return 1000;
            }

            @Override
            public int sqlMs() {
                return 100;
            }

            @Override
            public int callMs() {
                return 1000;
            }

            @Override
            public int cacheMs() {
                return 50;
            }
        };
    }

    @Bean
    public List<Analyzer> analyzers(HourlyReportStore store, MetricHourRank rank,
                                    SlowThresholdProvider thresholds) {
        List<Analyzer> analyzers = new ArrayList<>();
        analyzers.add(new TransactionAnalyzer(store));
        analyzers.add(new EventAnalyzer(store));
        analyzers.add(new ProblemAnalyzer(store, thresholds.urlMs(), thresholds.sqlMs(),
                thresholds.callMs(), thresholds.cacheMs()));
        analyzers.add(new HeartbeatAnalyzer(store));
        analyzers.add(new MetricAnalyzer(store, rank));
        analyzers.add(new DependencyAnalyzer(store));
        return analyzers;
    }

    @Bean
    public RealtimeConsumer realtimeConsumer(List<Analyzer> analyzers) {
        return new RealtimeConsumer(analyzers);
    }

    // ── 报表滚动链路（不依赖外部中间件）──────────────────────

    @Bean
    public com.neocat.analysis.infra.store.MinuteBucketReader minuteBucketReader(
            HourlyReportStore store, Supplier<ZoneId> platformZone) {
        return new com.neocat.analysis.infra.store.MinuteBucketReader(store, platformZone);
    }

    @Bean
    public com.neocat.analysis.domain.bucket.ReportBucketSinkPort reportBucketSinkPort() {
        // 内存替身：验证装配关系，不落 ClickHouse
        return new com.neocat.analysis.domain.bucket.ReportBucketSinkPort() {
            private final java.util.List<com.neocat.analysis.domain.bucket.AggregatedRow> written = new ArrayList<>();

            @Override
            public void writeMinuteBuckets(List<com.neocat.analysis.domain.bucket.AggregatedRow> rows) {
                written.addAll(rows);
            }

            @Override
            public void writeHourBuckets(List<com.neocat.analysis.domain.bucket.AggregatedRow> rows) {
                written.addAll(rows);
            }

            @Override
            public void writeDayBuckets(List<com.neocat.analysis.domain.bucket.AggregatedRow> rows) {
                written.addAll(rows);
            }

            @Override
            public void writeWeekBuckets(List<com.neocat.analysis.domain.bucket.AggregatedRow> rows) {
                written.addAll(rows);
            }

            @Override
            public void writeMonthBuckets(List<com.neocat.analysis.domain.bucket.AggregatedRow> rows) {
                written.addAll(rows);
            }

            @Override
            public List<com.neocat.analysis.domain.bucket.AggregatedRow> readBuckets(
                    com.neocat.analysis.domain.bucket.AggregationLevel level, Instant from, Instant to) {
                return written.stream()
                        .filter(row -> row.level() == level)
                        .filter(row -> !row.bucketStart().isBefore(from) && row.bucketStart().isBefore(to))
                        .toList();
            }

            @Override
            public long evictMinuteBucketsBefore(Instant threshold) {
                return 0;
            }

            @Override
            public long evictHourBucketsBefore(Instant threshold) {
                return 0;
            }

            @Override
            public long evictLongTermBucketsBefore(Instant threshold) {
                return 0;
            }
        };
    }

    @Bean
    public com.neocat.analysis.domain.schedule.ReportScheduler reportScheduler(
            com.neocat.analysis.infra.store.MinuteBucketReader reader,
            com.neocat.analysis.domain.bucket.ReportBucketSinkPort sink,
            Supplier<ZoneId> platformZone) {
        return new com.neocat.analysis.domain.schedule.ReportScheduler(reader, sink, platformZone);
    }
}
