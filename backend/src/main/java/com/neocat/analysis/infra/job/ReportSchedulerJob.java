package com.neocat.analysis.infra.job;

import com.neocat.common.time.clock.TimeProvider;

import com.neocat.analysis.infra.store.MinuteBucketReader;

import com.neocat.analysis.domain.bucket.AggregationLevel;
import com.neocat.analysis.domain.bucket.AggregatedRow;
import com.neocat.analysis.domain.schedule.ReportScheduler;
import com.neocat.ingest.config.IngestConfig;
import com.neocat.analysis.config.ReportConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.List;
import com.neocat.analysis.domain.bucket.ReportBucketSinkPort;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import org.springframework.context.annotation.DependsOn;

/**
 * 报表滚动调度（PRD 00 §10、PRD 03 §2.1，链路 23）。
 *
 * <p>把 {@link ReportScheduler} 的编排接到定时器上。触发时机与设计文档一致：
 * <pre>
 * 每分钟 :05        刷入上一个完成分钟
 * 整点后 :02        分钟桶 → 小时桶，并释放该小时内存
 * 每日 00:05        小时桶 → 日桶
 * 周一 00:10        小时桶 → 周桶
 * 每月 1 日 00:15   小时桶 → 月桶
 * 每日 02:00        按留存期清理超期桶
 * </pre>
 *
 * <p>每个任务都独立 try/catch：一个任务的失败不应让其他滚动任务停止。
 * 所有任务都只处理**已结束**的时间片，因此重复触发是安全的（幂等）。
 */
@Component
@DependsOn({"ingestConfig", "reportConfig"})
public class ReportSchedulerJob {

    private static final Logger log = LoggerFactory.getLogger(ReportSchedulerJob.class);

    private final ReportScheduler scheduler;

    private final ReportBucketSinkPort sink;

    private final MinuteBucketReader reader;


    private volatile long lastFlushedMinute;

    public ReportSchedulerJob(ReportScheduler scheduler, ReportBucketSinkPort sink,
                              MinuteBucketReader reader) {
        this.scheduler = scheduler;
        this.sink = sink;
        this.reader = reader;
        this.lastFlushedMinute = Long.MIN_VALUE;
    }
    /** 每秒检查动态延迟；同一已完成分钟只刷一次，失败允许下次重试。 */
    @Scheduled(fixedDelay = 1000)
    public synchronized void flushMinute() {
        Instant ready = TimeProvider.now().minusSeconds(ReportConfig.MINUTE_FLUSH_DELAY_SECONDS);
        long minute = ready.truncatedTo(ChronoUnit.MINUTES).toEpochMilli();
        if (minute <= lastFlushedMinute) return;
        runSafely("分钟落库", () -> {
            int written = scheduler.flushCompletedMinute(ready);
            scheduler.refreshLateHours(TimeProvider.now(), IngestConfig.ACCEPT_LATE_HOURS);
            lastFlushedMinute = minute;
            if (written > 0) {
                log.debug("已落库 {} 个分钟桶", written);
            }
        });
    }
    /** 整点聚合：小时结束后 2 分钟执行。 */
    @Scheduled(cron = "0 2 * * * *")
    public void rollupHour() {
        runSafely("小时聚合并释放内存", () -> {
            int written = scheduler.rollupCompletedHour(TimeProvider.now(), false);
            scheduler.refreshLateHours(TimeProvider.now(), IngestConfig.ACCEPT_LATE_HOURS);
            // Yesterday's final hour can still receive late observations after the initial
            // daily job. Rebuild its versioned snapshot after the late-hour refresh.
            scheduler.rollupCompletedDay(TimeProvider.now());
            log.info("小时聚合完成，写入 {} 行", written);
        });
    }
    /** 日聚合：每日 00:05。 */
    @Scheduled(cron = "0 5 0 * * *")
    public void rollupDay() {
        runSafely("日聚合", () -> log.info("日聚合完成，写入 {} 行",
                scheduler.rollupCompletedDay(TimeProvider.now())));
    }
    /** 周聚合：周一 00:10（平台时区周一为自然周边界）。 */
    @Scheduled(cron = "0 10 0 * * MON")
    public void rollupWeek() {
        runSafely("周聚合", () -> log.info("周聚合完成，写入 {} 行",
                scheduler.rollupCompletedWeek(TimeProvider.now())));
    }
    /** 月聚合：每月 1 日 00:15。 */
    @Scheduled(cron = "0 15 0 1 * *")
    public void rollupMonth() {
        runSafely("月聚合", () -> log.info("月聚合完成，写入 {} 行",
                scheduler.rollupCompletedMonth(TimeProvider.now())));
    }
    /**
     * 留存清理：每日 02:00。
     *
     * <p>三条留存线由运行参数决定，因此调整 Apollo 即可改变保留策略，
     * 不需要改代码或重启。
     */
    @Scheduled(cron = "0 0 2 * * *")
    public void evictExpired() {
        runSafely("留存清理", () -> {
            var result = scheduler.evictExpired(TimeProvider.now(),
                    ReportConfig.MINUTE_RETENTION_DAYS,
                    ReportConfig.HOUR_RETENTION_DAYS,
                    ReportConfig.LONG_TERM_RETENTION_MONTHS);
            log.info("留存清理完成：分钟桶 {} 行、小时桶 {} 行、日周月 {} 行",
                    result.getMinuteBuckets(), result.getHourBuckets(), result.getLongTermBuckets());
        });
    }
    /** 任务包一层异常隔离，保证单个任务失败不影响其他滚动任务。 */
    private void runSafely(String taskName, Runnable task) {
        try {
            task.run();
        } catch (Throwable t) {
            log.error("{} 失败，已跳过本次执行", taskName, t);
        }
    }
    /**
     * 手动触发一次完整滚动（运维用）。
     *
     * <p>用于补齐因停机造成的缺口：按顺序重放各层级聚合。
     */
    public void catchUp() {
        rollupHour();
        rollupDay();
        rollupWeek();
        rollupMonth();
    }
    /** 读取某层级的桶行（运维诊断用）。 */
    public List<AggregatedRow> readBuckets(AggregationLevel level, Instant from, Instant to) {
        return sink.readBuckets(level, from, to);
    }
    /** 当前小时内存中的序列数（运维诊断用）。 */
    public Map<String, Long> currentHourSeries() {
        return reader.seriesCountByService();
    }
}
