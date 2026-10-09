package com.neocat.query.domain.stat;

import java.math.BigDecimal;

import com.neocat.analysis.domain.bucket.AggregatedRow;
import com.neocat.analysis.domain.bucket.DurationDistribution;
import com.neocat.common.DecimalMath;

import java.util.List;
import java.util.Objects;

import org.apache.commons.collections4.CollectionUtils;
import org.springframework.modulith.NamedInterface;
import org.springframework.stereotype.Component;

/**
 * 统计项计算（PRD 03 §3、§4；技术方案 03 §8.1）。
 *
 * <p>核心不变式：**先合并分子与分布，再由合并结果计算**。
 * <ul>
 *   <li>Avg = ΣdurationSum ÷ Σcount（绝不平均各桶或各机器的 Avg）；</li>
 *   <li>FailureRate = ΣfailCount ÷ Σcount；</li>
 *   <li>QPS = Σcount ÷ 公共分母（绝不平均各机器 QPS）；</li>
 *   <li>分位 = 合并分布后重算（绝不平均各桶分位）；</li>
 *   <li>Min/Max 取真实极值，不参与求和。</li>
 * </ul>
 *
 * <p>无数据语义（PRD 03 §5）：
 * <ul>
 *   <li>没有任何行（序列不存在）→ 全部统计项返回 {@code null}；</li>
 *   <li>存在行但 count = 0（确认无调用）→ 次数类返回 0，耗时类与比例类返回 {@code null}。</li>
 * </ul>
 */
@NamedInterface("query")
@Component
public class StatCalculator {

    public BigDecimal compute(List<AggregatedRow> rows, Stat stat, long coveredSeconds) {
        return DecimalMath.result(computeIntermediate(rows, stat, coveredSeconds));
    }

    /**
     * 供后续公式继续计算，不在统计输入处提前做六位舍入。
     */
    public BigDecimal computeIntermediate(List<AggregatedRow> rows, Stat stat, long coveredSeconds) {
        if (CollectionUtils.isEmpty(rows)) {
            // 序列不存在：缺数，不是零
            return null;
        }
        Merged merged = merge(rows);
        return computeIntermediateFrom(merged, stat, coveredSeconds);
    }

    /**
     * 由已合并的中间结果计算统计项，供多桶聚合后重复使用。
     */
    public BigDecimal computeFrom(Merged merged, Stat stat, long coveredSeconds) {
        return DecimalMath.result(computeIntermediateFrom(merged, stat, coveredSeconds));
    }

    public BigDecimal computeIntermediateFrom(Merged merged, Stat stat, long coveredSeconds) {
        return switch (stat) {
            // 次数类：确认无调用时为 0
            case HITS -> BigDecimal.valueOf(merged.getCount());
            case FAILURES -> BigDecimal.valueOf(merged.getFailCount());

            // 速率类
            case QPS -> coveredSeconds <= 0 ? null : DecimalMath.divide(merged.getCount(), coveredSeconds);
            case FAILURE_RATE ->
                    merged.getCount() == 0 ? null : DecimalMath.divide(merged.getFailCount(), merged.getCount());

            // 耗时类：无调用时无值
            case AVG -> merged.getCount() == 0 ? null : DecimalMath.divide(merged.getDurationSum(), merged.getCount());
            case MIN -> merged.getCount() == 0 ? null : BigDecimal.valueOf(merged.getDurationMin());
            case MAX -> merged.getCount() == 0 ? null : BigDecimal.valueOf(merged.getDurationMax());

            // 分位类
            case TP50, TP90, TP95, TP99, TP999, TP9999 ->
                    merged.getDistribution().percentileIntermediate(stat.percentileFraction());
        };
    }

    /**
     * 合并多行的分子与分布。分布逐段相加，因此后续分位估算天然满足
     * 「合并原始分布后重新计算」的要求。
     */
    public Merged merge(List<AggregatedRow> rows) {
        long count = 0;
        long failCount = 0;
        long durationSum = 0;
        long durationMin = Long.MAX_VALUE;
        long durationMax = Long.MIN_VALUE;
        DurationDistribution merged = null;

        for (AggregatedRow row : rows) {
            count += row.count();
            failCount += row.failCount();
            durationSum += row.durationSum();
            if (row.count() > 0) {
                durationMin = Math.min(durationMin, row.durationMin());
                durationMax = Math.max(durationMax, row.durationMax());
            }
            merged = Objects.isNull(merged) ? row.distribution().copy() : merged.merge(row.distribution());
        }

        return new Merged(count, failCount, durationSum,
                durationMin == Long.MAX_VALUE ? 0L : durationMin,
                durationMax == Long.MIN_VALUE ? 0L : durationMax,
                Objects.isNull(merged) ? new DurationDistribution() : merged);
    }

}
