package com.neocat.analysis.domain.schedule;

import com.neocat.analysis.domain.bucket.AggregatedRow;
import com.neocat.analysis.domain.bucket.AggregationLevel;
import com.neocat.analysis.domain.bucket.MinuteBucketSource;
import com.neocat.analysis.domain.bucket.ReportBucketSinkPort;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.function.Supplier;
import org.apache.commons.collections4.CollectionUtils;
import java.time.DayOfWeek;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.ToString;
import org.springframework.modulith.NamedInterface;
import org.springframework.stereotype.Component;

/**
 * 报表滚动编排（PRD 00 §10、PRD 03 §2.1，链路 23）。
 *
 * <p>本类只做**编排**，不碰存储与调度框架，因此可以完全离线测试：
 * <pre>
 * 每分钟   : 刷入上一个已完成分钟，补刷允许迟到的历史小时
 * 整点后   : 分钟桶 → 小时桶
 * 每日后   : 小时桶 → 日桶
 * 每周后   : 小时桶 → 周桶
 * 每月后   : 小时桶 → 月桶
 * 每日清理 : 按留存期删除超期桶
 * </pre>
 *
 * <p>关键设计：**只刷已完成的时间片**。
 * 当前分钟/小时仍在写入，所有入口以 {@code now} 为界取「已结束」的时间片；
 * 允许迟到窗口中的桶保留内存并刷新版本，窗口关闭后才释放。
 *
 * @param reader  当前小时报表读取
 * @param sink    桶写入
 * @param zone    平台时区（决定自然日/周/月边界）
 */
@NamedInterface("analysis")
@Getter
@EqualsAndHashCode
@ToString
@Component
public class ReportScheduler {
    private final MinuteBucketSource reader;

    private final ReportBucketSinkPort sink;

    private final Supplier<ZoneId> zone;

    public ReportScheduler(MinuteBucketSource reader, ReportBucketSinkPort sink, Supplier<ZoneId> zone) {
        this.reader = reader;
        this.sink = sink;
        this.zone = zone;
    }

    /**
     * 每分钟执行：把上一个完整分钟刷入库。
     *
     * @param now 当前时刻
     * @return 落库的桶行数
     */
    public int flushCompletedMinute(Instant now) {
        Instant completedMinute = now.truncatedTo(ChronoUnit.MINUTES).minusSeconds(60);
        List<AggregatedRow> rows = reader.readMinute(completedMinute);
        if (CollectionUtils.isEmpty(rows)) {
            return 0;
        }
        sink.writeMinuteBuckets(rows);
        return rows.size();
    }
    /**
     * 整点后执行：把刚结束的小时聚合为小时桶，并释放该小时的内存。
     *
     * @param now 当前时刻
     * @return 写入的小时桶行数
     */
    public int rollupCompletedHour(Instant now) {
        return rollupCompletedHour(now, true);
    }
    public int rollupCompletedHour(Instant now, boolean releaseMemory) {
        Instant completedHour = now.atZone(zone.get()).truncatedTo(ChronoUnit.HOURS)
                .minusHours(1).toInstant();
        List<AggregatedRow> minuteRows = reader.readHour(completedHour);
        if (CollectionUtils.isEmpty(minuteRows)) {
            if (releaseMemory) reader.clearHour(completedHour);
            return 0;
        }
        List<AggregatedRow> hourRows = reader.aggregate(minuteRows, AggregationLevel.HOUR);
        // Refresh minute snapshots too: late samples received since the first minute flush
        // must remain queryable after in-memory buckets are released. Snapshot reads deduplicate.
        sink.writeMinuteBuckets(minuteRows);
        sink.writeHourBuckets(hourRows);
        if (releaseMemory) reader.clearHour(completedHour);
        return hourRows.size();
    }
    /** Refresh still-accepted historical hours, then finalize the oldest now-closed hour.
     * Retaining buckets until ingest's late window closes prevents late snapshots replacing
     * earlier observations with a smaller post-clear delta under the same writer identity.
     */
    public void refreshLateHours(Instant now, int acceptLateHours) {
        Instant currentHour = now.atZone(zone.get()).truncatedTo(ChronoUnit.HOURS).toInstant();
        int hours = Math.max(1, acceptLateHours);
        for (int offset = 1; offset <= hours; offset++) {
            Instant hour = currentHour.minusSeconds(offset * 3600L);
            List<AggregatedRow> rows = reader.readHour(hour);
            if (CollectionUtils.isNotEmpty(rows)) {
                sink.writeMinuteBuckets(rows);
                sink.writeHourBuckets(reader.aggregate(rows, AggregationLevel.HOUR));
            }
            if (offset == hours) reader.clearHour(hour);
        }
    }
    /**
     * 每日执行：把刚结束的日的已完成小时聚合为日桶。
     *
     * <p>只取**已结束**的小时：当天最后一个小时可能仍在写入。
     */
    public int rollupCompletedDay(Instant now) {
        ZonedDateTime today = now.atZone(zone.get()).truncatedTo(ChronoUnit.DAYS);
        Instant start = today.minusDays(1).toInstant();
        Instant end = today.toInstant();
        List<AggregatedRow> hourRows = sink.readBuckets(AggregationLevel.HOUR, start, end);
        if (CollectionUtils.isEmpty(hourRows)) {
            return 0;
        }
        List<AggregatedRow> dayRows = reader.aggregate(hourRows, AggregationLevel.DAY);
        sink.writeDayBuckets(dayRows);
        return dayRows.size();
    }
    /**
     * 每周执行（平台时区周一 00:00 之后）：把刚结束的自然周聚合为周桶。
     */
    public int rollupCompletedWeek(Instant now) {
        ZonedDateTime monday = now.atZone(zone.get()).truncatedTo(ChronoUnit.DAYS)
                .with(DayOfWeek.MONDAY);
        Instant end = monday.toInstant();
        Instant start = monday.minusWeeks(1).toInstant();
        List<AggregatedRow> hourRows = sink.readBuckets(AggregationLevel.HOUR, start, end);
        if (CollectionUtils.isEmpty(hourRows)) {
            return 0;
        }
        List<AggregatedRow> weekRows = reader.aggregate(hourRows, AggregationLevel.WEEK);
        sink.writeWeekBuckets(weekRows);
        return weekRows.size();
    }
    /**
     * 每月执行（平台时区月初之后）：把刚结束的自然月聚合为月桶。
     */
    public int rollupCompletedMonth(Instant now) {
        ZonedDateTime firstOfMonth = now.atZone(zone.get()).truncatedTo(ChronoUnit.DAYS)
                .withDayOfMonth(1);
        Instant end = firstOfMonth.toInstant();
        Instant start = firstOfMonth.minusMonths(1).toInstant();
        List<AggregatedRow> hourRows = sink.readBuckets(AggregationLevel.HOUR, start, end);
        if (CollectionUtils.isEmpty(hourRows)) {
            return 0;
        }
        List<AggregatedRow> monthRows = reader.aggregate(hourRows, AggregationLevel.MONTH);
        sink.writeMonthBuckets(monthRows);
        return monthRows.size();
    }
    /**
     * 每日清理超期桶（PRD 00 §10）。
     *
     * <p>三条留存线：分钟桶与小时桶 30 天、日周月 13 个月。
     * 原始树的 7 天留存由 trace 模块负责，不在此处。
     *
     * @param now                 当前时刻
     * @param minuteRetentionDays 分钟桶留存天数
     * @param hourRetentionDays   小时桶留存天数
     * @param longTermMonths      日周月留存月数
     */
    public EvictionResult evictExpired(Instant now, int minuteRetentionDays,
                                       int hourRetentionDays, int longTermMonths) {
        ZonedDateTime local = now.atZone(zone.get());
        long minuteEvicted = sink.evictMinuteBucketsBefore(
                local.minusDays(minuteRetentionDays).toInstant());
        long hourEvicted = sink.evictHourBucketsBefore(
                local.minusDays(hourRetentionDays).toInstant());
        long longTermEvicted = sink.evictLongTermBucketsBefore(
                local.minusMonths(longTermMonths).toInstant());
        return new EvictionResult(minuteEvicted, hourEvicted, longTermEvicted);
    }
    /**
     * 分钟落库延迟（观测用）：完成分钟点与当前时刻的差距应在容差内。
     */
    public Duration currentLag(Instant now) {
        Instant completedMinute = now.truncatedTo(ChronoUnit.MINUTES).minusSeconds(60);
        return Duration.between(completedMinute, now);
    }
    /** 清理结果。 */
    @NamedInterface("analysis")
    @Getter
    @EqualsAndHashCode
    @ToString
    public static class EvictionResult {
        private final long minuteBuckets;

        private final long hourBuckets;

        private final long longTermBuckets;

        public EvictionResult(long minuteBuckets, long hourBuckets, long longTermBuckets) {
            this.minuteBuckets = minuteBuckets;
            this.hourBuckets = hourBuckets;
            this.longTermBuckets = longTermBuckets;
        }

    }
}

