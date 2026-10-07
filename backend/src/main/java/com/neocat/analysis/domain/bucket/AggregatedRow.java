package com.neocat.analysis.domain.bucket;

import java.time.Instant;
import java.util.Objects;

/**
 * 已持久化的一行聚合数据（对应 ClickHouse 各层级桶表的一行）。
 */
@org.springframework.modulith.NamedInterface("analysis")
public class AggregatedRow {

    private final SeriesKey key;

    private final Instant bucketStart;

    private final AggregationLevel level;

    private long count;

    private long failCount;

    private long durationSum;

    private long durationMin;

    private long durationMax;

    private double valueSum;

    private long valueCount;

    private boolean valueCountMissing;

    private Double valueLast;

    private Instant valueLastTime;

    private long coveredSeconds;

    private com.neocat.analysis.domain.bucket.DurationDistribution distribution;

    public AggregatedRow(SeriesKey key, Instant bucketStart, AggregationLevel level, long coveredSeconds) {
        this.key = key;
        this.bucketStart = bucketStart;
        this.level = level;
        this.coveredSeconds = coveredSeconds;
        this.durationMin = Long.MAX_VALUE;
        this.durationMax = Long.MIN_VALUE;
        this.distribution = new com.neocat.analysis.domain.bucket.DurationDistribution();
    }
    public SeriesKey key() {
        return key;
    }
    public Instant bucketStart() {
        return bucketStart;
    }
    public AggregationLevel level() {
        return level;
    }
    public long coveredSeconds() {
        return coveredSeconds;
    }
    public void setCoveredSeconds(long coveredSeconds) {
        this.coveredSeconds = coveredSeconds;
    }
    public long count() {
        return count;
    }
    public long failCount() {
        return failCount;
    }
    public long durationSum() {
        return durationSum;
    }
    public long durationMin() {
        return durationMin == Long.MAX_VALUE ? 0L : durationMin;
    }
    public long durationMax() {
        return durationMax == Long.MIN_VALUE ? 0L : durationMax;
    }
    public double valueSum() {
        return valueSum;
    }
    public long valueCount() {
        return valueCount;
    }
    public void addCount(long count, long failCount, long durationSum, long durationMin, long durationMax) {
        this.count += count;
        this.failCount += failCount;
        this.durationSum += durationSum;
        this.durationMin = Math.min(this.durationMin, durationMin);
        this.durationMax = Math.max(this.durationMax, durationMax);
    }
    /**
     * 耗时分布（跨桶分位合并的基础）。
     *
     * <p>由产生该行的分析器填充；聚合时逐段相加，绝不对已算出的分位取平均。
     */
    public com.neocat.analysis.domain.bucket.DurationDistribution distribution() {
        return distribution;
    }
    /** 记录一个耗时样本到本行分布。 */
    public void recordDuration(long durationMs) {
        distribution.record(durationMs);
    }
    /** 用已合并的分布整体替换本行分布（跨桶聚合时使用）。 */
    public void setDistribution(com.neocat.analysis.domain.bucket.DurationDistribution merged) {
        this.distribution = merged;
    }
    public void addValue(double valueSum, long valueCount) {
        this.valueSum += valueSum;
        this.valueCount += valueCount;
    }
    public Double valueLast() { return valueLast; }
    public boolean valueCountMissing() { return valueCountMissing; }
    public void markValueCountMissing(boolean missing) { valueCountMissing |= missing; }
    public Instant valueLastTime() { return valueLastTime; }
    public void mergeLastValue(Double value, Instant time) {
        if (Objects.isNull(value) || Objects.isNull(time)) return;
        if (Objects.isNull(valueLastTime) || time.isAfter(valueLastTime)
                || (time.equals(valueLastTime) && value > valueLast)) {
            valueLast = value;
            valueLastTime = time;
        }
    }
    /** 平均耗时；无调用时无值。 */
    public Double averageDuration() {
        return count == 0 ? null : (double) durationSum / count;
    }
    /** 失败率；无调用时无值。 */
    public Double failureRate() {
        return count == 0 ? null : (double) failCount / count;
    }
    /**
     * QPS 分母（PRD 03 §4 三态）：
     * <ul>
     *   <li>当前未结束小时：整点至当前的秒数（由调用方传入 coveredSeconds）；</li>
     *   <li>完整历史小时：3600；</li>
     *   <li>日 / 周 / 月 / 自定义：桶实际覆盖秒数。</li>
     * </ul>
     */
    public Double qps() {
        return coveredSeconds <= 0 ? null : (double) count / coveredSeconds;
    }
}








