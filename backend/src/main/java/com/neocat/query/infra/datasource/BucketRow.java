package com.neocat.query.infra.datasource;

import java.math.BigDecimal;

import com.neocat.analysis.domain.bucket.AggregationLevel;

import java.time.Instant;

import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.ToString;

/**
 * 一个桶行（与 {@code nc_minute_bucket} / {@code nc_hour_bucket} 列对应）。
 */
@Getter
@EqualsAndHashCode
@ToString
public class BucketRow {
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
