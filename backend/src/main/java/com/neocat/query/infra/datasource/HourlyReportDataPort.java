package com.neocat.query.infra.datasource;

import com.neocat.query.infra.port.ReportDataPort;

import com.neocat.analysis.domain.bucket.AggregatedRow;
import com.neocat.analysis.domain.bucket.AggregationLevel;
import com.neocat.analysis.domain.bucket.HourlyReportStore;
import com.neocat.analysis.domain.bucket.MinuteBucket;
import com.neocat.analysis.domain.bucket.SeriesKey;
import com.neocat.common.time.bucket.Bucket;
import com.neocat.common.time.bucket.Granularity;
import com.neocat.common.time.bucket.TimeBucketResolver;
import com.neocat.common.time.range.RangeSpec;

import java.time.Instant;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 基于进程内当前小时报表的读取实现（技术方案 01-architecture.md §6.5）。
 *
 * <p>只覆盖当前小时；历史范围由 ClickHouse 实现补充到同一接口，
 * 因此「桶内聚合不变式」「缺数语义」只有一份上层实现。
 *
 * <p>关键约定：
 * <ul>
 *   <li>**桶起点按调用方给定的 {@code granularity} 对齐**。内存里存的是分钟桶，
 *       当调用方要 10 分钟 / 1 小时 / 1 天的点时，需要把范围内的分钟桶**卷起来**
 *       再返回；否则调用方按桶起点取数时大部分取不到，趋势图会缺一大片；</li>
 *   <li>**无数据的桶不产生行**。缺口由查询层依据「有多少桶、命中多少行」判定，
 *       而不是靠伪造 count=0 的行，这样「确认无调用（0）」与「缺数据（缺口）」
 *       从数据源头就分开。</li>
 * </ul>
 */
public class HourlyReportDataPort implements ReportDataPort {

    private final HourlyReportStore store;

    private final TimeBucketResolver buckets;

    private final java.util.function.Supplier<ZoneId> zone;

    private final com.neocat.analysis.domain.metric.MetricLabelMetadata metadata;

    public HourlyReportDataPort(HourlyReportStore store, TimeBucketResolver buckets,
                                java.util.function.Supplier<ZoneId> zone) {
        this(store, buckets, zone, null);
    }
    public HourlyReportDataPort(HourlyReportStore store, TimeBucketResolver buckets,
                                java.util.function.Supplier<ZoneId> zone,
                                com.neocat.analysis.domain.metric.MetricLabelMetadata metadata) {
        this.store = store;
        this.buckets = buckets;
        this.zone = zone;
        this.metadata = metadata;
    }
    @Override
    public List<AggregatedRow> rows(String kind, String service, String type, String name,
                                    Instant from, Instant to, Granularity granularity,
                                    List<String> instances) {
        List<String> targets = (instances == null || instances.isEmpty())
                ? List.of(SeriesKey.ALL)
                : instances;

        // 以 resolve 得到的桶序列为准，保证与查询层的时间对齐一致
        List<Bucket> bucketList = buckets.resolve(
                new RangeSpec.Explicit(from, to, granularity), zone.get());

        List<AggregatedRow> rows = new ArrayList<>();
        for (SeriesKey key : store.seriesKeys()) {
            if (!matches(key, kind, service, type) || !targets.contains(key.getInstance())) {
                continue;
            }
            String seriesName = key.getKind() == com.neocat.analysis.domain.bucket.SeriesKind.METRIC
                    ? key.getMetricLabels() : key.getName();
            if (name != null && !name.equals(seriesName)) {
                continue;
            }
            for (Bucket bucket : bucketList) {
                AggregatedRow row = fold(key, bucket, granularity);
                if (row != null) {
                    rows.add(row);
                }
            }
        }
        return rows;
    }
    /**
     * 把一个目标桶覆盖的分钟桶合并成一行；整段没有任何数据时返回 {@code null}。
     *
     * <p>返回值恒定锚定在 {@code bucket.start()}：调用方按桶起点与自己的桶序列
     * 逐一对齐，锚点必须是目标桶起点而不是「第一个有数据的分钟」。
     *
     * <p>粒度就是 1 分钟时只有一个源桶，走的是同一条路径 —— 这样「合并分子与分布」
     * 只有一处实现，不会因为粒度不同而出现两套口径。
     */
    private AggregatedRow fold(SeriesKey key, Bucket bucket, Granularity granularity) {
        // 目标桶覆盖的分钟数：粒度 ≤ 1 分钟时只有 1 个源桶
        long stepSeconds = granularity.seconds();
        long spanSeconds = Math.min(stepSeconds, java.time.Duration.between(bucket.getStart(), bucket.getEnd()).getSeconds());
        int minuteCount = (int) Math.max(1, (spanSeconds + 59) / 60);

        AggregatedRow folded = null;
        for (int i = 0; i < minuteCount; i++) {
            MinuteBucket minute = store.bucket(key, bucket.getStart().plusSeconds(i * 60L));
            if (minute == null || (minute.count() == 0 && minute.valueCount() == 0)) {
                continue;
            }
            if (folded == null) {
                // 恒定锚定目标桶起点：调用方按桶起点对齐
                folded = new AggregatedRow(key, bucket.getStart(), AggregationLevel.MINUTE, 0);
            }
            folded.addCount(minute.count(), minute.failCount(), minute.durationSum(),
                    minute.durationMin(), minute.durationMax());
            synchronized (minute) {
                folded.addValue(minute.valueSum(), minute.valueCount());
                folded.mergeLastValue(minute.valueLast(), minute.valueLastTime());
            }
            // 分布逐段相加：分位在查询期由合并后的分布重算（PRD 03 §3）
            folded.setDistribution(folded.distribution().merge(minute.distribution()));
        }

        if (folded == null) {
            // 无行 = 缺口来源；不制造 count=0 的行
            return null;
        }
        folded.setCoveredSeconds(bucket.getCoveredSeconds());
        return folded;
    }
    @Override
    public List<String> instancesWithData(String kind, String service, Instant from, Instant to) {
        Set<String> result = new LinkedHashSet<>();
        for (SeriesKey key : store.seriesKeys()) {
            if (!service.equals(key.getService())) {
                continue;
            }
            if (!key.getKind().name().equalsIgnoreCase(kind)) {
                continue;
            }
            if (SeriesKey.ALL.equals(key.getInstance())) {
                continue;
            }
            if (hasDataInRange(key, from, to)) {
                result.add(key.getInstance());
            }
        }
        return List.copyOf(result);
    }
    @Override
    public List<String> namesOf(String kind, String service, String type, Instant from, Instant to) {
        Set<String> result = new LinkedHashSet<>();
        for (SeriesKey key : store.seriesKeys()) {
            if (!matches(key, kind, service, type)) {
                continue;
            }
            if (hasDataInRange(key, from, to)) {
                result.add(key.getKind() == com.neocat.analysis.domain.bucket.SeriesKind.METRIC
                        ? key.getMetricLabels() : key.getName());
            }
        }
        return List.copyOf(result);
    }
    @Override
    public List<String> typesOf(String kind, String service, Instant from, Instant to) {
        Set<String> result = new LinkedHashSet<>();
        for (SeriesKey key : store.seriesKeys()) {
            if (!matches(key, kind, service, null)) {
                continue;
            }
            if (hasDataInRange(key, from, to)) {
                result.add(key.getType());
            }
        }
        return List.copyOf(result);
    }
    /**
     * 丢弃质量事件由路由从 ClickHouse 查询，包括当前小时；
     * Metric 合并身份从采集期元数据读取，不等待小时排名固化。
     */
    @Override
    public boolean droppedAt(String kind, String service, String type, String name, Instant bucketStart) {
        return false;
    }
    @Override
    public boolean mergedIntoOther(String service, String metricName, String labels, Instant hourStart) {
        return metadata != null && metadata.entries(hourStart, hourStart.plusSeconds(3600)).stream().anyMatch(e ->
                e.getService().equals(service) && e.getMetric().equals(metricName) && e.getCanonicalLabels().equals(labels) && e.isMerged());
    }

    // ── 内部 ─────────────────────────────────────────────────

    private boolean matches(SeriesKey key, String kind, String service, String type) {
        if (!service.equals(key.getService())) {
            return false;
        }
        if (!key.getKind().name().equalsIgnoreCase(kind)) {
            return false;
        }
        return type == null || type.equals(key.getType());
    }
    /** 该序列在 [from, to) 内是否存在非空分钟桶。 */
    private boolean hasDataInRange(SeriesKey key, Instant from, Instant to) {
        Instant cursor = from.truncatedTo(ChronoUnit.MINUTES);
        while (cursor.isBefore(to)) {
            MinuteBucket bucket = store.bucket(key, cursor);
            if (bucket != null && (bucket.count() > 0 || bucket.valueCount() > 0)) {
                return true;
            }
            cursor = cursor.plusSeconds(60);
        }
        return false;
    }



    private com.neocat.analysis.domain.bucket.SeriesKind parseKind(String kind) {
        return com.neocat.analysis.domain.bucket.SeriesKind.valueOf(kind.toUpperCase(java.util.Locale.ROOT));
    }
}

