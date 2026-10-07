package com.neocat.query.domain.metric;

import com.neocat.query.domain.series.Point;
import com.neocat.query.domain.series.Quality;
import com.neocat.query.domain.series.Series;
import com.neocat.query.domain.stat.Stat;
import com.neocat.query.domain.stat.StatCalculator;

import com.neocat.analysis.domain.bucket.AggregatedRow;
import com.neocat.common.time.bucket.Bucket;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Metric 查询（PRD 04 §2–5，链路 21）。
 *
 * <p>核心规则（PRD 04 §3）——**每个自然小时独立排名**的结果会在跨小时查询时产生缺口：
 * <pre>
 * 该小时独立保留       → 显示实际值（OK）
 * 该小时被并入 other   → 显示缺口，标记 MERGED_OTHER
 * 既无数据也未并入     → 缺口，标记 NO_DATA
 * </pre>
 *
 * <p>两条不可违反的约束：
 * <ul>
 *   <li>**不用 other 值冒充具体组合**：合并小时的值为 {@code null}，即使 other 序列在
 *       该小时有数据也不参与；</li>
 *   <li>**不把缺口显示为 0**：缺口点的 value 恒为 {@code null}。</li>
 * </ul>
 *
 * <p>`other` 自身是独立序列，查询它时按普通序列处理，不标记缺口。
 */
@org.springframework.modulith.NamedInterface("query")
public class MetricQueryService {

    public Series series(String service, String metricName, String labels,
                         List<AggregatedRow> rows,
                         List<Bucket> buckets,
                         Set<Long> mergedHours,
                         Stat stat, long coveredSeconds) {
        StatCalculator calculator = new StatCalculator();
        Map<Long, AggregatedRow> byBucket = indexByBucket(rows, service, metricName, labels);

        List<Point> points = new ArrayList<>(buckets.size());
        for (Bucket bucket : buckets) {
            long start = bucket.getStart().toEpochMilli();
            long end = bucket.getEnd().toEpochMilli();
            long covered = bucket.getCoveredSeconds();

            if (mergedIntoOtherFor(labels, start, mergedHours)) {
                // 合并小时：缺口，绝不回落到 other 的值
                points.add(new Point(start, end, null, Quality.MERGED_OTHER, covered));
                continue;
            }

            AggregatedRow row = byBucket.get(start);
            if (row == null) {
                points.add(new Point(start, end, null, Quality.NO_DATA, covered));
                continue;
            }
            Double value = calculator.compute(List.of(row), stat, covered);
            points.add(new Point(start, end, value, qualityOf(row, value), covered));
        }

        return new Series(service, "METRIC", metricName, labels, stat,
                buckets.isEmpty() ? 0 : buckets.get(0).totalSeconds(),
                buckets.isEmpty() ? 0 : buckets.get(0).getStart().toEpochMilli(),
                buckets.isEmpty() ? 0 : buckets.get(buckets.size() - 1).getEnd().toEpochMilli(),
                List.copyOf(points));
    }
    /** 该小时对该标签组合是否被并入 other。 */
    public boolean mergedIntoOther(Long bucketStart, Set<Long> mergedHours) {
        return bucketStart != null && mergedHours != null && mergedHours.contains(bucketStart);
    }

    // ── 内部 ─────────────────────────────────────────────────

    /**
     * 判断是否为「并入 other」缺口。
     *
     * <p>查询目标本身是 {@code __OTHER__} 时不适用该规则：
     * other 序列在各小时本来就代表聚合结果。
     */
    private boolean mergedIntoOtherFor(String labels, long bucketStart, Set<Long> mergedHours) {
        if ("__OTHER__".equals(labels)) {
            return false;
        }
        return mergedIntoOther(bucketStart, mergedHours);
    }
    /**
     * 按桶起点索引行，**同时校验序列身份**。
     *
     * <p>必须按 (服务, 指标名, 标签串) 过滤：只按桶索引会让查询「city=北京」拿到
     * 「city=上海」的值——这是比缺口更危险的错误，会让用户看到不存在的数据。
     */
    private Map<Long, AggregatedRow> indexByBucket(List<AggregatedRow> rows,
                                                   String service, String metricName, String labels) {
        Map<Long, AggregatedRow> byBucket = new HashMap<>();
        if (rows == null) {
            return byBucket;
        }
        for (AggregatedRow row : rows) {
            if (!matchesIdentity(row, service, metricName, labels)) {
                continue;
            }
            byBucket.putIfAbsent(row.bucketStart().toEpochMilli(), row);
        }
        return byBucket;
    }
    /** 行的序列身份是否与查询目标一致。 */
    private boolean matchesIdentity(AggregatedRow row, String service, String metricName, String labels) {
        if (service != null && !service.equals(row.key().getService())) {
            return false;
        }
        if (metricName != null && !metricName.equals(row.key().getType())) {
            return false;
        }
        if (labels != null && !labels.equals(row.key().getMetricLabels())) {
            return false;
        }
        return true;
    }
    private Quality qualityOf(AggregatedRow row, Double value) {
        if (value == null) {
            return Quality.NO_DATA;
        }
        if (row.count() == 0) {
            return Quality.ZERO;
        }
        return Quality.OK;
    }
    /** 便捷方法：由行的时间戳推导小时起点（测试与调用方共用）。 */
    public static long hourStartOf(long timestamp) {
        return Instant.ofEpochMilli(timestamp)
                .truncatedTo(java.time.temporal.ChronoUnit.HOURS)
                .toEpochMilli();
    }
}
