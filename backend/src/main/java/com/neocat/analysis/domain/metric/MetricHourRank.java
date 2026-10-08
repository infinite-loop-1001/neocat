package com.neocat.analysis.domain.metric;

import com.neocat.analysis.domain.bucket.SeriesKey;

import com.neocat.ingest.domain.tree.MessageTree;

import java.time.Instant;
import java.util.Map;
import java.util.Set;
import org.springframework.modulith.NamedInterface;

/**
 * Metric 小时内排名表（PRD 04 §2–3）。
 *
 * <p>规则（每个自然小时独立排名）：
 * <ol>
 *   <li>标签键按稳定顺序规范化，相同键值组合视为同一序列；</li>
 *   <li>按上报次数从高到低排名；</li>
 *   <li>保留最多 1000 个真实标签组合；</li>
 *   <li>其余组合进入一个 {@code other} 序列；</li>
 *   <li>11:00 重新从空排名开始，同一组合可能从独立序列变成 other，或反向变化。</li>
 * </ol>
 */
@NamedInterface("analysis")
public interface MetricHourRank {

    /**
     * 记录一次上报并返回该次应归属的序列标签。
     *
     * @return 真实标签串，或 {@link SeriesKey#OTHER_LABELS} 表示并入 other
     */
    String record(String service, String metricName, String canonicalLabels, Instant eventTime);

    /** 该小时被保留为独立序列的标签集合。 */
    Set<String> promotedLabels(String service, String metricName, Instant hourStart);

    /** 该小时某标签组合是否被并入 other（用于跨小时缺口表达）。 */
    boolean mergedIntoOther(String service, String metricName, String canonicalLabels, Instant hourStart);

    /** 固化当前小时的排名（整点后调用）：此时刻后到达的迟到数据按固化结果归属。 */
    void finalizeHour(Instant hourStart);

    boolean finalized(Instant hourStart);

    default void clearBefore(Instant boundary) { }
}
