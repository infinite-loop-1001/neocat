package com.neocat.query.infra.datasource;

import java.math.BigDecimal;

import com.neocat.analysis.domain.bucket.AggregationLevel;

import java.time.Instant;
import java.util.List;

import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.ToString;

/**
 * ClickHouse 报表查询（技术方案 06 §10）。
 *
 * <p>把 SQL 与行映射集中在此，使 {@link ClickHouseReportDataPort} 只负责
 * 「调用 → 转换 → 组装」，便于单测用替身验证转换规则，而不需要真实 ClickHouse。
 *
 * <p>实现应参数化执行（{@code ?} 占位），不做字符串拼接。
 */
public interface ClickHouseReportQuery {

    /**
     * 查询分钟桶行。
     *
     * @param service  服务
     * @param kind     报表类型
     * @param type     分类；null 表示不限
     * @param name     名称；null 表示不限
     * @param instance 实例；{@code all} 表示全机器聚合行
     * @param from     起点（含）
     * @param to       终点（不含）
     */
    List<BucketRow> minuteRows(String service, String kind, String type, String name,
                               String instance, Instant from, Instant to);

    /**
     * 查询小时桶行（用于小时粒度）。
     */
    List<BucketRow> hourRows(String service, String kind, String type, String name,
                             String instance, Instant from, Instant to);

    /**
     * 查询日桶行（用于日及以上粒度）。
     *
     * <p>周 / 月粒度也走日桶：日桶留存 13 个月，足够覆盖，且读侧再卷一次
     * 比新增两张表更省。周 / 月边界由 {@code TimeBucketResolver} 统一对齐。
     */
    List<BucketRow> dayRows(String service, String kind, String type, String name,
                            String instance, Instant from, Instant to);

    /**
     * 该范围内有数据的实例列表。
     */
    List<String> distinctInstances(String service, String kind, Instant from, Instant to);

    /**
     * 该范围内出现过的分类列表。
     */
    List<String> distinctTypes(String service, String kind, Instant from, Instant to);

    /**
     * 某分类下出现过的名称列表。
     */
    List<String> distinctNames(String service, String kind, String type, Instant from, Instant to);

    /**
     * 该桶是否存在队列满丢弃质量事件（{@code nc_quality_event}）。
     */
    boolean hasDropEvent(String service, String kind, String type, String name, Instant bucketStart);

    default boolean hasDropEvents(String service, Instant from, Instant to) {
        for (Instant minute = from; minute.isBefore(to); minute = minute.plusSeconds(60)) {
            if (hasDropEvent(service, null, null, null, minute)) return true;
        }
        return false;
    }

    /**
     * 某 Metric 具体序列在某小时是否被并入 other（{@code nc_metric_hour_rank}）。
     */
    boolean mergedIntoOther(String service, String metricName, String labels, Instant hourStart);

    /**
     * 一个桶行（与 {@code nc_minute_bucket} / {@code nc_hour_bucket} 列对应）。
     */
    @Getter
    @EqualsAndHashCode
    @ToString
    class BucketRow {
        private final String service;

        private final String kind;

        private final String type;

        private final String name;

        private final String instance;

        private final String problemCategory;

        private final String metricLabels;

        private final Instant bucketStart;

        private final AggregationLevel level;

        private final long count;

        private final long failCount;

        private final long durationSum;

        private final long durationMin;

        private final long durationMax;

        private final BigDecimal valueSum;

        private final long valueCount;

        private final long[] distribution;

        private final long coveredSeconds;

        private final BigDecimal valueLast;

        private final Instant valueLastTime;

        private final boolean valueCountMissing;

        public BucketRow(String service, String kind, String type, String name, String instance, String problemCategory, String metricLabels, Instant bucketStart, AggregationLevel level, long count, long failCount, long durationSum, long durationMin, long durationMax, BigDecimal valueSum, long valueCount, long[] distribution, long coveredSeconds, BigDecimal valueLast, Instant valueLastTime, boolean valueCountMissing) {
            this.service = service;
            this.kind = kind;
            this.type = type;
            this.name = name;
            this.instance = instance;
            this.problemCategory = problemCategory;
            this.metricLabels = metricLabels;
            this.bucketStart = bucketStart;
            this.level = level;
            this.count = count;
            this.failCount = failCount;
            this.durationSum = durationSum;
            this.durationMin = durationMin;
            this.durationMax = durationMax;
            this.valueSum = valueSum;
            this.valueCount = valueCount;
            this.distribution = distribution;
            this.coveredSeconds = coveredSeconds;
            this.valueLast = valueLast;
            this.valueLastTime = valueLastTime;
            this.valueCountMissing = valueCountMissing;
        }

        public BucketRow(String service, String kind, String type, String name, String instance,
                         String problemCategory, String metricLabels, Instant bucketStart, AggregationLevel level,
                         long count, long failCount, long durationSum, long durationMin, long durationMax,
                         BigDecimal valueSum, long valueCount, long[] distribution, long coveredSeconds,
                         BigDecimal valueLast, Instant valueLastTime) {
            this(service, kind, type, name, instance, problemCategory, metricLabels, bucketStart, level,
                    count, failCount, durationSum, durationMin, durationMax, valueSum, valueCount,
                    distribution, coveredSeconds, valueLast, valueLastTime, false);
        }

        public BucketRow(String service, String kind, String type, String name, String instance,
                         String problemCategory, String metricLabels, Instant bucketStart, AggregationLevel level,
                         long count, long failCount, long durationSum, long durationMin, long durationMax,
                         BigDecimal valueSum, long valueCount, long[] distribution, long coveredSeconds) {
            this(service, kind, type, name, instance, problemCategory, metricLabels, bucketStart, level,
                    count, failCount, durationSum, durationMin, durationMax, valueSum, valueCount,
                    distribution, coveredSeconds, null, null);
        }
    }
}