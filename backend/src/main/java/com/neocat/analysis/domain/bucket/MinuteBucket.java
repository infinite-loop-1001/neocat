package com.neocat.analysis.domain.bucket;

/**
 * 内存分钟桶（技术方案 01 §6.5）。
 *
 * <p>聚合不变式：**先合并分子与分布，再计算 avg / failureRate / 分位**，
 * 绝不平均子桶的平均值或分位（PRD 03 §3 明确禁止）。
 *
 * <p>无调用语义（PRD 03 §5）：
 * <ul>
 *   <li>次数类（count / failCount）显示 0；</li>
 *   <li>耗时类与比例类（Avg / FailureRate / 分位）显示**无值**，即返回 {@code null}。</li>
 * </ul>
 */
@org.springframework.modulith.NamedInterface("analysis")
public class MinuteBucket {

    private long count;

    private long failCount;

    private long durationSum;

    private long durationMin;

    private long durationMax;

    private double valueSum;

    private double valueMin;

    private double valueMax;

    private long valueCount;

    private Double valueLast;

    private java.time.Instant valueLastTime;

    private final DurationDistribution distribution;

    private final DurationDistribution valueDistribution;

    public MinuteBucket() {
        this.durationMin = Long.MAX_VALUE;
        this.durationMax = Long.MIN_VALUE;
        this.valueMin = Double.POSITIVE_INFINITY;
        this.valueMax = Double.NEGATIVE_INFINITY;
        this.distribution = new DurationDistribution();
        this.valueDistribution = new DurationDistribution();
    }

    public void addSuccess(long durationMs) {
        add(durationMs, false);
    }
    public void addFailure(long durationMs) {
        add(durationMs, true);
    }
    /**
     * 只计数、不记耗时（Event 专用）。
     * 保持分布为空，使查询期对 Event 请求分位时得到无值而非伪精确值。
     */
    public void addCountOnly(boolean failure) {
        count++;
        if (failure) {
            failCount++;
        }
    }
    private void add(long durationMs, boolean failure) {
        count++;
        if (failure) {
            failCount++;
        }
        // 耗时统计只对非负耗时有意义；负数已在 ingest 校验阶段拒绝，这里做防御性处理。
        if (durationMs >= 0) {
            durationSum += durationMs;
            durationMin = Math.min(durationMin, durationMs);
            durationMax = Math.max(durationMax, durationMs);
            distribution.record(durationMs);
        }
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
    /** 真实最小耗时；无调用时返回 0。 */
    public long durationMin() {
        return count == 0 ? 0L : (durationMin == Long.MAX_VALUE ? 0L : durationMin);
    }
    /** 真实最大耗时；无调用时返回 0。 */
    public long durationMax() {
        return count == 0 ? 0L : (durationMax == Long.MIN_VALUE ? 0L : durationMax);
    }
    public DurationDistribution distribution() {
        return distribution;
    }
    /** 平均耗时；无调用时返回 null（PRD 03 §5：无调用时 Avg 显示无值）。 */
    public Double averageDuration() {
        return count == 0 ? null : (double) durationSum / count;
    }
    /** 失败率；无调用时返回 null。 */
    public Double failureRate() {
        return count == 0 ? null : (double) failCount / count;
    }
    /** QPS：桶内总次数 ÷ 桶实际覆盖秒数（PRD 03 §3、§4）。 */
    public Double qps(long coveredSeconds) {
        if (coveredSeconds <= 0) {
            return null;
        }
        return (double) count / coveredSeconds;
    }

    // ── 数值型指标（Metric / Heartbeat 使用） ─────────────────

    /**
     * 记录一个数值型观测（gauge）。
     *
     * <p>与耗时不同：数值型指标需要 sum / min / max / avg，且分位基于**原始数值分布**。
     * 这里同时累加数值统计与分布（分位基于桶内原始数值分布，不平均子桶分位 —— PRD 04 §9）。
     */
    public synchronized void addValue(double value) {
        valueSum += value;
        valueMin = Math.min(valueMin, value);
        valueMax = Math.max(valueMax, value);
        valueCount++;
        // 数值分位基于桶内原始数值分布（PRD 04 §9），与耗时分位严格分开：
        // 复用同一分布会把耗时样本混进数值分位，反之亦然。
        valueDistribution.record(Math.round(value));
    }
    public synchronized void addValue(double value, java.time.Instant eventTime) {
        addValue(value);
        if (valueLastTime == null || eventTime.isAfter(valueLastTime)
                || (eventTime.equals(valueLastTime) && value > valueLast)) {
            valueLast = value;
            valueLastTime = eventTime;
        }
    }
    public synchronized Double valueLast() { return valueLast; }
    public synchronized java.time.Instant valueLastTime() { return valueLastTime; }

    /**
     * 数值分布（Metric / Heartbeat 的数值分位来源）。
     *
     * <p>与 {@link #distribution()}（耗时分位）严格分离，避免两类样本互相污染。
     */
    public DurationDistribution valueDistribution() {
        return valueDistribution;
    }
    public synchronized long valueCount() {
        return valueCount;
    }
    public synchronized double valueSum() {
        return valueSum;
    }
    /** 数值最小值；无观测时返回 0。 */
    public double valueMin() {
        return valueCount == 0 ? 0.0d : valueMin;
    }
    /** 数值最大值；无观测时返回 0。 */
    public double valueMax() {
        return valueCount == 0 ? 0.0d : valueMax;
    }
    /** 数值平均；无观测时返回 null（无值语义）。 */
    public Double valueAverage() {
        return valueCount == 0 ? null : valueSum / valueCount;
    }
}









