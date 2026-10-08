package com.neocat.analysis.domain.bucket;

import java.math.BigDecimal;


import java.time.Instant;
import java.util.Map;
import java.util.Set;

import org.springframework.modulith.NamedInterface;

/**
 * 当前小时报表面板（进程内）：按序列键聚合分钟桶。
 *
 * <p>提供序列目录，供 catalog 的 {@code SeriesPresence} 判定
 * 「当前小时是否有数据」。
 */
@NamedInterface("analysis")
public interface HourlyReportStore {

    /**
     * 追加一次调用到对应分钟桶。
     */
    void add(SeriesKey key, Instant eventTime, long durationMs, boolean failure);

    /**
     * 追加一次**无耗时语义**的事件（Event 专用）：只累加次数与失败数，不触碰耗时统计。
     *
     * <p>PRD 03 §8：Event 不提供耗时、分布和分位。若走 {@link #add} 并传 0，
     * 会把 0ms 样本带进分布，导致查询期出现本不该存在的分位值。
     */
    void addCountOnly(SeriesKey key, Instant eventTime, boolean failure);

    /**
     * 读取某序列在某分钟的桶；不存在返回 null。
     */
    MinuteBucket bucket(SeriesKey key, Instant minuteStart);

    /**
     * 当前小时已有的序列键（用于目录动态过滤）。
     */
    Set<SeriesKey> seriesKeys();

    /**
     * 按服务的当前小时序列计数（观测用）。
     */
    Map<String, Long> seriesCountByService();

    /**
     * 记录一个数值型观测到指定序列（Metric / Heartbeat 使用）。
     */
    void addValue(SeriesKey key, Instant eventTime, BigDecimal value);

    /**
     * 清空某一小时的分钟桶（整点滚动后调用）。
     */
    void clearHour(Instant hourStart);
}
