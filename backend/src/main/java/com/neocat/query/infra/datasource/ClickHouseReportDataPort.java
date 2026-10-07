package com.neocat.query.infra.datasource;

import com.neocat.query.infra.port.ReportDataPort;

import com.neocat.analysis.domain.bucket.AggregatedRow;
import com.neocat.analysis.domain.bucket.DurationDistribution;
import com.neocat.analysis.domain.bucket.SeriesKey;
import com.neocat.analysis.domain.bucket.SeriesKind;
import com.neocat.common.time.bucket.Bucket;
import com.neocat.common.time.bucket.Granularity;
import com.neocat.common.time.bucket.TimeBucketResolver;
import com.neocat.common.time.range.RangeSpec;

import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.apache.commons.collections4.CollectionUtils;

/**
 * 基于 ClickHouse 的报表读取实现（技术方案 06 §10）。
 *
 * <p>职责边界：
 * <ul>
 *   <li><b>按调用方给定的粒度选源表</b>：粒度 ≥ 1 天查日桶表，≥ 1 小时查小时桶表，
 *       其余查分钟桶表。选表依据是**粒度**而不是范围长度 —— 范围长不代表点要粗，
 *       由范围长度推断会让「1 个月看每 10 分钟」这类请求拿不到对齐的桶；</li>
 *   <li>**把源桶卷到调用方要的桶**：分钟桶表只有分钟粒度的行，
 *       请求 5/10/20 分钟时必须在读侧合并，否则调用方按桶起点取数只能命中
 *       正好落在边界上的少数分钟，趋势图会大面积缺失；</li>
 *   <li>把 {@link ClickHouseReportQuery.BucketRow} 转成 {@link AggregatedRow}，
 *       并用 {@link DurationDistribution#fromSegments} 还原分布；</li>
 *   <li>**无数据的桶不产生行**（与内存实现同一约定），
 *       缺口由查询层判定，不靠伪造 count=0 的行。</li>
 * </ul>
 *
 * <p>本类不直接执行 SQL，所有查询经 {@link ClickHouseReportQuery} 抽象，
 * 因此转换逻辑可以完全离线测试。
 */
public class ClickHouseReportDataPort implements ReportDataPort {

    private final ClickHouseReportQuery query;

    private final TimeBucketResolver buckets;

    private final java.util.function.Supplier<ZoneId> zone;

    private final java.time.Clock clock;

    public ClickHouseReportDataPort(ClickHouseReportQuery query) {
        this(query, new com.neocat.common.time.bucket.DefaultTimeBucketResolver(), () -> ZoneId.of("UTC"));
    }
    public ClickHouseReportDataPort(ClickHouseReportQuery query, TimeBucketResolver buckets,
                                    java.util.function.Supplier<ZoneId> zone) {
        this(query, buckets, zone, java.time.Clock.systemUTC());
    }
    public ClickHouseReportDataPort(ClickHouseReportQuery query, TimeBucketResolver buckets,
                                    java.util.function.Supplier<ZoneId> zone, java.time.Clock clock) {
        this.query = query;
        this.buckets = buckets;
        this.zone = zone;
        this.clock = clock;
    }
    @Override
    public List<AggregatedRow> metricSourceRows(String service, String metric, Instant from, Instant to,
                                               Granularity granularity) {
        return queryFor(granularity, service, "METRIC", metric, null, SeriesKey.ALL, from, to).stream()
                .map(ClickHouseReportDataPort::toAggregatedRow).toList();
    }
    @Override
    public List<AggregatedRow> rows(String kind, String service, String type, String name,
                                    Instant from, Instant to, Granularity granularity,
                                    List<String> instances) {
        List<String> targets = (CollectionUtils.isEmpty(instances))
                ? List.of(SeriesKey.ALL)
                : instances;

        List<AggregatedRow> rows = new ArrayList<>();

        for (String instance : targets) {
            List<ClickHouseReportQuery.BucketRow> bucketRows =
                    queryFor(granularity, service, kind, type, name, instance, from, to);

            for (ClickHouseReportQuery.BucketRow bucket : bucketRows) {
                if (bucket.getCount() == 0 && bucket.getValueCount() == 0) {
                    // 空桶不产生行：缺数据不能被伪造成 count=0 的「确认无调用」
                    continue;
                }
                rows.add(toAggregatedRow(bucket));
            }
        }

        // 源桶粒度细于目标桶时（分钟表 + 5/10/20 分钟）按目标桶归并
        return foldToTarget(rows, from, to, granularity);
    }
    /** 按请求粒度选源表：日及以上查日桶，小时查小时桶，其余查分钟桶。 */
    private List<ClickHouseReportQuery.BucketRow> queryFor(Granularity granularity,
                                                           String service, String kind, String type,
                                                           String name, String instance,
                                                           Instant from, Instant to) {
        long seconds = granularity.seconds();
        if (seconds >= Granularity.DAY_1.seconds()) {
            // Today's completed hours are not in the day table yet. Do not leave today blank
            // when a month window crosses the current-hour boundary.
            Instant today = clock.instant().atZone(zone.get()).toLocalDate().atStartOfDay(zone.get()).toInstant();
            if (from.isBefore(today) && to.isAfter(today)) {
                var result = new ArrayList<>(query.dayRows(service, kind, type, name, instance, from, today));
                result.addAll(query.hourRows(service, kind, type, name, instance, today, to));
                return result;
            }
            if (!from.isBefore(today)) return query.hourRows(service, kind, type, name, instance, from, to);
            return query.dayRows(service, kind, type, name, instance, from, to);
        }
        if (seconds >= Granularity.HOUR_1.seconds()) {
            return query.hourRows(service, kind, type, name, instance, from, to);
        }
        return query.minuteRows(service, kind, type, name, instance, from, to);
    }
    /**
     * 把源行并到调用方要的目标桶。
     *
     * <p>桶边界一律走 {@link TimeBucketResolver}，与查询层的对齐口径同源；
     * 合并时**逐段相加分布**，保证分位仍由合并后的分布重算（PRD 03 §3）。
     */
    private List<AggregatedRow> foldToTarget(List<AggregatedRow> rows, Instant from, Instant to,
                                             Granularity granularity) {
        List<Bucket> targetBuckets = buckets.resolve(new RangeSpec.Explicit(from, to, granularity), zone.get());
        @lombok.Getter
        @lombok.EqualsAndHashCode
        @lombok.ToString
        class FoldKey {
            private final SeriesKey series;

            private final Instant start;

            public FoldKey(SeriesKey series, Instant start) {
                this.series = series;
                this.start = start;
            }
 }
        Map<FoldKey, AggregatedRow> byStart = new LinkedHashMap<>();
        List<AggregatedRow> result = new ArrayList<>();

        for (AggregatedRow row : rows) {
            Bucket target = bucketContaining(targetBuckets, row.bucketStart());
            if (Objects.isNull(target)) {
                // 落在请求范围外（边界对齐导致）：保持原样，不静默丢弃
                FoldKey identity = new FoldKey(row.key(), row.bucketStart());
                if (!byStart.containsKey(identity)) {
                    byStart.put(identity, row);
                    result.add(row);
                }
                continue;
            }
            FoldKey key = new FoldKey(row.key(), target.getStart());
            AggregatedRow existing = byStart.get(key);
            if (Objects.isNull(existing)) {
                existing = new AggregatedRow(row.key(), target.getStart(), row.level(), target.getCoveredSeconds());
                byStart.put(key, existing);
                result.add(existing);
            }
            existing.addCount(row.count(), row.failCount(), row.durationSum(),
                    row.durationMin(), row.durationMax());
            existing.addValue(row.valueSum(), row.valueCount());
            existing.markValueCountMissing(row.valueCountMissing());
            existing.mergeLastValue(row.valueLast(), row.valueLastTime());
            existing.setDistribution(existing.distribution().merge(row.distribution()));
        }
        return result;
    }
    private Bucket bucketContaining(List<Bucket> candidates, Instant start) {
        for (Bucket bucket : candidates) {
            if (bucket.contains(start)) {
                return bucket;
            }
        }
        return null;
    }
    @Override
    public List<String> instancesWithData(String kind, String service, Instant from, Instant to) {
        return query.distinctInstances(service, kind, from, to);
    }
    @Override
    public List<String> namesOf(String kind, String service, String type, Instant from, Instant to) {
        return query.distinctNames(service, kind, type, from, to);
    }
    @Override
    public List<String> typesOf(String kind, String service, Instant from, Instant to) {
        return query.distinctTypes(service, kind, from, to);
    }
    @Override
    public boolean droppedAt(String kind, String service, String type, String name, Instant bucketStart) {
        return query.hasDropEvent(service, kind, type, name, bucketStart);
    }
    @Override
    public boolean droppedBetween(String kind, String service, String type, String name, Instant from, Instant to) {
        return query.hasDropEvents(service, from, to);
    }
    @Override
    public boolean mergedIntoOther(String service, String metricName, String labels, Instant hourStart) {
        return query.mergedIntoOther(service, metricName, labels, hourStart);
    }

    // ── 转换 ─────────────────────────────────────────────────

    /**
     * 桶行 → 聚合行。
     *
     * <p>分布用 {@code fromSegments} 还原为分箱模式，因此它仍然参与
     * 「合并后重算分位」的不变式，而不是带着已算好的分位四处传递。
     */
    static AggregatedRow toAggregatedRow(ClickHouseReportQuery.BucketRow bucket) {
        SeriesKey key = new SeriesKey(
                bucket.getService(),
                parseKind(bucket.getKind()),
                bucket.getType(),
                bucket.getName(),
                bucket.getInstance(),
                Objects.isNull(bucket.getProblemCategory()) ? "" : bucket.getProblemCategory(),
                Objects.isNull(bucket.getMetricLabels()) ? "" : bucket.getMetricLabels());

        AggregatedRow row = new AggregatedRow(key, bucket.getBucketStart(),
                bucket.getLevel(), bucket.getCoveredSeconds());
        row.addCount(bucket.getCount(), bucket.getFailCount(), bucket.getDurationSum(),
                bucket.getDurationMin(), bucket.getDurationMax());
        row.addValue(bucket.getValueSum(), bucket.getValueCount());
        row.markValueCountMissing(bucket.isValueCountMissing());
        row.mergeLastValue(bucket.getValueLast(), bucket.getValueLastTime());
        row.setDistribution(DurationDistribution.fromSegments(bucket.getDistribution()));
        return row;
    }
    private static SeriesKind parseKind(String kind) {
        return SeriesKind.valueOf(kind.toUpperCase(java.util.Locale.ROOT));
    }
}

