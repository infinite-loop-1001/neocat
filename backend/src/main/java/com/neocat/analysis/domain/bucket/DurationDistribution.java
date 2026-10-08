package com.neocat.analysis.domain.bucket;

import java.util.Arrays;
import java.util.Objects;
import java.math.BigDecimal;
import java.math.RoundingMode;

import com.neocat.common.DecimalMath;
import org.springframework.modulith.NamedInterface;

/**
 * 耗时分位分布：16 段对数分箱 + 低基数精确值双轨（技术方案 01 §6.7、06 §10.1.1）。
 *
 * <p>分箱定义：第 i 段覆盖 {@code [2^i, 2^(i+1))} 毫秒，i = 0..15，即 1ms–65536ms。
 * 超出上界的样本落入最后一段；下界以下的样本（含 0ms）落入第一段。
 *
 * <p>聚合不变式：合并时**逐段相加**，再由合并结果估算分位。
 * 这样跨桶、跨机器、跨层级的合并都保持「先合并分布，再算分位」的正确顺序
 * （PRD 03 §3 禁止平均子桶分位）。
 *
 * <p>双轨策略：桶内样本数不超过精确值上限时同时保留原始值，分位完全准确；
 * 超过上限后丢弃精确值、只保留分箱，分位由分箱插值估算。两个分布合并时，
 * 只要有一侧不是精确模式，结果就不是精确模式（避免用部分精确值冒充全局精确）。
 */
@NamedInterface("analysis")
public class DurationDistribution {

    public static final int SEGMENTS = 16;

    /**
     * 精确值保留上限，与 {@code neocat.report.exact-values.max} 默认值一致。
     */
    public static final int DEFAULT_EXACT_LIMIT = 200;

    private final long[] segments;

    private final long[] exactValues;

    private int exactCount;

    private boolean exactMode;

    private long total;

    public DurationDistribution() {
        this(DEFAULT_EXACT_LIMIT);
    }

    public DurationDistribution(int exactLimit) {
        this.segments = new long[SEGMENTS];
        this.exactValues = exactLimit > 0 ? new long[exactLimit] : null;
        this.exactMode = exactLimit > 0;
    }

    /**
     * 记录一个耗时样本。
     */
    public void record(long durationMs) {
        long value = Math.max(0L, durationMs);
        segments[segmentOf(value)]++;
        total++;
        if (exactMode) {
            if (Objects.nonNull(exactValues) && exactCount < exactValues.length) {
                exactValues[exactCount++] = value;
            } else {
                // 超过上限：退化为纯分箱模式
                exactMode = false;
            }
        }
    }

    /**
     * 本分布是否由精确值支撑（分位完全准确）。
     */
    public boolean exact() {
        return exactMode;
    }

    public long count() {
        return total;
    }

    /**
     * 分箱快照（长度 16）；返回副本以防外部修改内部状态。
     */
    public long[] segments() {
        return Arrays.copyOf(segments, SEGMENTS);
    }

    /**
     * 精确值快照；非精确模式或未记录时返回空数组。
     */
    public long[] exactValues() {
        if (!exactMode || Objects.isNull(exactValues) || exactCount == 0) {
            return new long[0];
        }
        return Arrays.copyOf(exactValues, exactCount);
    }

    /**
     * 合并另一个分布：逐段相加。
     *
     * <p>精确值只在两侧都精确时合并；否则放弃精确值，退化为分箱模式。
     */
    public DurationDistribution merge(DurationDistribution other) {
        DurationDistribution merged = new DurationDistribution(
                exactMode && other.exactMode && Objects.nonNull(exactValues) && Objects.nonNull(other.exactValues)
                        ? exactValues.length + other.exactValues.length
                        : 0);
        System.arraycopy(segments, 0, merged.segments, 0, SEGMENTS);
        for (int i = 0; i < SEGMENTS; i++) {
            merged.segments[i] += other.segments[i];
        }
        merged.total = total + other.total;

        if (exactMode && other.exactMode && merged.exactMode) {
            for (int i = 0; i < exactCount; i++) {
                merged.exactValues[merged.exactCount++] = exactValues[i];
            }
            for (int i = 0; i < other.exactCount; i++) {
                merged.exactValues[merged.exactCount++] = other.exactValues[i];
            }
        } else {
            merged.exactMode = false;
        }
        return merged;
    }

    /**
     * 从已持久化的分箱数组重建分布（技术方案 06 §2：{@code distribution} 列）。
     *
     * <p>用途：把 ClickHouse 读回的 16 段直方图还原成可参与合并与分位估算的对象。
     * 重建后的分布处于**分箱模式**（无精确值），因为原始值未随桶保留。
     * 长度为 16 之外的输入按段数截断或补零，避免历史格式变化导致读取失败。
     *
     * @param persistedSegments 逐段样本计数
     */
    public static DurationDistribution fromSegments(long[] persistedSegments) {
        DurationDistribution distribution = new DurationDistribution(0);
        if (Objects.isNull(persistedSegments)) {
            return distribution;
        }
        System.arraycopy(persistedSegments, 0, distribution.segments, 0,
                Math.min(persistedSegments.length, SEGMENTS));
        for (int i = 0; i < SEGMENTS; i++) {
            distribution.total += distribution.segments[i];
        }
        return distribution;
    }

    /**
     * 复制当前分布（含精确值模式），用于跨桶聚合时不修改源对象。
     */
    public DurationDistribution copy() {
        DurationDistribution copy = new DurationDistribution(Objects.isNull(exactValues) ? 0 : exactValues.length);
        System.arraycopy(segments, 0, copy.segments, 0, SEGMENTS);
        copy.total = total;
        copy.exactCount = exactCount;
        copy.exactMode = exactMode;
        if (exactMode && Objects.nonNull(exactValues)) {
            System.arraycopy(exactValues, 0, copy.exactValues, 0, exactCount);
        }
        return copy;
    }

    /**
     * 估算分位；空分布返回 {@code null}（PRD 03 §5：无调用时分位显示无值）。
     *
     * <p>精确模式下直接从排序后的原始值取第 {@code ceil(p * n)} 个；
     * 分箱模式下定位到覆盖率超过 {@code p} 的段，段内按线性插值估计。
     */
    public BigDecimal percentile(BigDecimal p) {
        return DecimalMath.result(percentileIntermediate(p));
    }

    /**
     * 后续统计或公式的中间输入，不提前舍入到六位。
     */
    public BigDecimal percentileIntermediate(BigDecimal p) {
        if (total == 0) {
            return null;
        }
        BigDecimal clamped = p.max(BigDecimal.ZERO).min(BigDecimal.ONE);
        if (exactMode && Objects.nonNull(exactValues) && exactCount > 0) {
            long[] sorted = Arrays.copyOf(exactValues, exactCount);
            Arrays.sort(sorted);
            int index = clamped.multiply(BigDecimal.valueOf(sorted.length))
                    .setScale(0, RoundingMode.CEILING).intValueExact() - 1;
            return BigDecimal.valueOf(sorted[Math.max(0, Math.min(sorted.length - 1, index))]);
        }
        return percentileFromSegments(clamped);
    }

    /**
     * 分桶模式下的分位估计：定位分段并按段内累计权重线性插值。
     */
    private BigDecimal percentileFromSegments(BigDecimal p) {
        long target = p.multiply(BigDecimal.valueOf(total)).setScale(0, RoundingMode.CEILING).longValueExact();
        if (target <= 0) {
            target = 1;
        }
        long cumulative = 0;
        for (int i = 0; i < SEGMENTS; i++) {
            long bucketCount = segments[i];
            if (bucketCount == 0) {
                continue;
            }
            if (cumulative + bucketCount >= target) {
                BigDecimal lower = segmentLowerBound(i);
                BigDecimal upper = segmentUpperBound(i);
                BigDecimal within = DecimalMath.divide(target - cumulative, bucketCount);
                return lower.add(upper.subtract(lower).multiply(within));
            }
            cumulative += bucketCount;
        }
        return segmentUpperBound(SEGMENTS - 1);
    }

    /**
     * 第 i 段下界 = 2^i（i=0 时为 0）。
     */
    private BigDecimal segmentLowerBound(int index) {
        return index == 0 ? BigDecimal.ZERO : BigDecimal.valueOf(1L << index);
    }

    /**
     * 第 i 段上界 = 2^(i+1)。
     */
    private BigDecimal segmentUpperBound(int index) {
        return BigDecimal.valueOf(1L << (index + 1));
    }

    /**
     * 样本值 → 分段索引。
     */
    private int segmentOf(long value) {
        if (value <= 1L) {
            return 0;
        }
        int index = 63 - Long.numberOfLeadingZeros(value);   // floor(log2(value))
        return Math.min(SEGMENTS - 1, index);
    }
}
