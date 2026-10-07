package com.neocat.analysis.domain.bucket;

import java.time.Instant;
import java.util.List;

/**
 * 当前小时报表读取口（调度器侧）。
 *
 * <p>与 {@code HourlyReportStore} 的区别：本接口服务于**落库**，
 * 只关心「某时间片有哪些行」与「聚合到上一层」，不暴露单点写入。
 * 这样调度器不依赖内存实现，测试可用替身驱动。
 */
@org.springframework.modulith.NamedInterface("analysis")
public interface MinuteBucketSource {

    /** 读取某分钟的全部序列行；无数据时返回空列表。 */
    List<AggregatedRow> readMinute(Instant minuteStart);

    /** 读取某完整小时的全部分钟行。 */
    List<AggregatedRow> readHour(Instant hourStart);

    /** 把分钟行聚合到目标层级（合并分子与分布后再算 avg / 分位）。 */
    List<AggregatedRow> aggregate(List<AggregatedRow> minuteRows, AggregationLevel level);

    /** 清空已定稿小时的内存。 */
    void clearHour(Instant hourStart);
}
