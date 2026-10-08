package com.neocat.analysis.domain.bucket;

import java.time.Instant;
import java.util.List;
import org.springframework.modulith.NamedInterface;

/**
 * 桶写入与清理口（调度器侧）。
 *
 * <p>与适配层的 {@code ReportBucketSink} 分开定义，使调度器只依赖领域层契约，
 * 不引入对适配层的编译依赖。
 */
@NamedInterface("analysis")
public interface ReportBucketSinkPort {

    void writeMinuteBuckets(List<AggregatedRow> rows);

    void writeHourBuckets(List<AggregatedRow> rows);

    void writeDayBuckets(List<AggregatedRow> rows);

    void writeWeekBuckets(List<AggregatedRow> rows);

    void writeMonthBuckets(List<AggregatedRow> rows);

    /** 读取某层级的桶行用于向上聚合。 */
    List<AggregatedRow> readBuckets(AggregationLevel level, Instant from, Instant to);

    long evictMinuteBucketsBefore(Instant threshold);

    long evictHourBucketsBefore(Instant threshold);

    long evictLongTermBucketsBefore(Instant threshold);
}
