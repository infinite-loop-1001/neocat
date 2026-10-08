package com.neocat.query.domain.series;

import com.neocat.query.domain.stat.Stat;

import java.util.Objects;
import org.springframework.modulith.NamedInterface;
import org.springframework.stereotype.Component;

/**
 * 数据质量判定（PRD 00 §6、PRD 03 §5、PRD 06 §6）。
 *
 * <p>判定优先级（严格顺序，先命中者胜出）：
 * <pre>
 * 1. dropped           → DROPPED      采集丢失，最严重，压过一切
 * 2. mergedIntoOther   → MERGED_OTHER Metric 具体序列该小时被并入 other
 * 3. !seriesExists     → NO_DATA      序列不存在：缺数据，不是零
 * 4. realtime          → REALTIME     当前仍在写入，尚不能断言确认无调用
 * 5. partial(未满桶)    → PARTIAL      部分覆盖
 * 6. count == 0        → ZERO         数据完整且确认无调用
 * 7. 其他              → OK
 * </pre>
 *
 * <p>为什么顺序如此重要：
 * <ul>
 *   <li>若先判 count == 0，队列满导致的丢数会被显示成「确认无调用」——
 *       这正是 PRD 00 §6 反复强调的「缺数据不等于零」，会让值班人员误判服务正常；</li>
 *   <li>实时桶尚未结束，不能断言确认无调用；</li>
 *   <li>部分覆盖桶只覆盖了一部分时间，同样不能断言。</li>
 * </ul>
 */
@NamedInterface("query")
@Component
public class QualityResolver {

    public Quality resolve(QualityInput input) {
        if (Objects.isNull(input)) {
            return Quality.NO_DATA;
        }
        if (input.isDropped()) {
            return Quality.DROPPED;
        }
        if (input.isMergedIntoOther()) {
            return Quality.MERGED_OTHER;
        }
        if (!input.isSeriesExists()) {
            return Quality.NO_DATA;
        }
        if (input.isRealtime()) {
            return Quality.REALTIME;
        }
        if (input.isPartial() && input.getCoveredSeconds() < 60L) {
            return Quality.PARTIAL;
        }
        if (input.getCount() == 0) {
            return Quality.ZERO;
        }
        return Quality.OK;
    }
    /**
     * 该点位对某统计项是否呈现为「有值」。
     *
     * <p>PRD 03 §5 原文：
     * <ul>
     *   <li>「桶内确认无调用：Hits、Failures、<b>QPS</b> 显示 0」；</li>
     *   <li>「无调用时 Avg、<b>Failure Rate</b>、分位显示无值」。</li>
     * </ul>
     *
     * <p>注意 QPS 与 FailureRate 单位同为 RATE，但无调用时的表现相反：
     * QPS 显示 0，FailureRate 显示无值。因此这里必须按统计项逐一判定，
     * 不能用单位整体归类。
     */
    public boolean hasValue(Quality quality, Stat stat) {
        if (quality.gap()) {
            return false;
        }
        if (Objects.equals(quality, Quality.ZERO)) {
            return zeroHasValue(stat);
        }
        return true;
    }
    /** 确认无调用时该统计项是否仍有值。 */
    private boolean zeroHasValue(Stat stat) {
        return switch (stat) {
            case HITS, FAILURES, QPS -> true;
            case AVG, MIN, MAX, FAILURE_RATE,
                 TP50, TP90, TP95, TP99, TP999, TP9999 -> false;
        };
    }
}
