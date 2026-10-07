package com.neocat.analysis.infra.store;

import com.neocat.analysis.domain.bucket.HourlyReportStore;
import com.neocat.analysis.domain.bucket.MinuteBucket;
import com.neocat.analysis.domain.bucket.SeriesKey;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.Objects;

/**
 * 进程内当前小时报表（技术方案 01-architecture.md §6.5）。
 *
 * <p>实现要点：
 * <ul>
 *   <li>外层按序列键分片、内层按分钟分桶，写路径无锁化（{@code ConcurrentHashMap} + 计数累加）；</li>
 *   <li>整点滚动时由 {@code clearHour} 清空已定稿的小时，避免内存无界增长；</li>
 *   <li>{@code seriesKeys} 供目录动态过滤：只有当前小时有数据的服务/实例才展示
 *       （PRD 02 §5「无数据不展示」）。</li>
 * </ul>
 */
@org.springframework.stereotype.Component
@org.springframework.context.annotation.DependsOn("reportConfig")
public class InMemoryHourlyReportStore implements HourlyReportStore {

    private final Map<SeriesKey, Map<Instant, MinuteBucket>> buckets;

    public InMemoryHourlyReportStore() {
        this.buckets = new ConcurrentHashMap<>();
    }

    @Override
    public void add(SeriesKey key, Instant eventTime, long durationMs, boolean failure) {
        MinuteBucket bucket = bucketFor(key, eventTime);
        if (failure) {
            bucket.addFailure(durationMs);
        } else {
            bucket.addSuccess(durationMs);
        }
    }
    @Override
    public void addCountOnly(SeriesKey key, Instant eventTime, boolean failure) {
        bucketFor(key, eventTime).addCountOnly(failure);
    }
    @Override
    public void addValue(SeriesKey key, Instant eventTime, double value) {
        bucketFor(key, eventTime).addValue(value, eventTime);
    }
    @Override
    public MinuteBucket bucket(SeriesKey key, Instant minuteStart) {
        Map<Instant, MinuteBucket> byMinute = buckets.get(key);
        return Objects.isNull(byMinute) ? null : byMinute.get(minuteStart.truncatedTo(ChronoUnit.MINUTES));
    }
    @Override
    public Set<SeriesKey> seriesKeys() {
        return Set.copyOf(buckets.keySet());
    }
    @Override
    public Map<String, Long> seriesCountByService() {
        Map<String, Long> counts = new HashMap<>();
        buckets.keySet().forEach(key -> counts.merge(key.getService(), 1L, Long::sum));
        return counts;
    }
    @Override
    public void clearHour(Instant hourStart) {
        Instant from = hourStart.truncatedTo(ChronoUnit.HOURS);
        Instant to = from.plusSeconds(3600);
        buckets.values().forEach(byMinute ->
                byMinute.keySet().removeIf(minute -> !minute.isBefore(from) && minute.isBefore(to)));
    }
    private MinuteBucket bucketFor(SeriesKey key, Instant eventTime) {
        Instant minute = eventTime.truncatedTo(ChronoUnit.MINUTES);
        return buckets
                .computeIfAbsent(key, k -> new ConcurrentHashMap<>())
                .computeIfAbsent(minute, m -> new MinuteBucket());
    }
}
