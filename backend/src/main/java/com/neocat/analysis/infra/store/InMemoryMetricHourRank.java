package com.neocat.analysis.infra.store;

import com.google.common.collect.Sets;
import com.neocat.analysis.domain.metric.MetricHourRank;
import com.neocat.analysis.domain.bucket.SeriesKey;
import com.neocat.common.config.MetricConfig;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.Objects;

/**
 * Metric 小时内排名（技术方案 01-architecture.md §6.4、06 §4）。
 *
 * <p>语义（PRD 04 §2–3）：
 * <ul>
 *   <li>每个自然小时独立排名；11:00 从空排名重新开始；</li>
 *   <li>名额未满时新出现的组合先到先得，晋升为独立序列；</li>
 *   <li>固化（整点）后按上报次数降序取前 N，其余并入 {@code other}；</li>
 *   <li>固化后到达的迟到数据按固化结果归属；</li>
 *   <li>{@code mergedIntoOther} 供查询层把具体组合的该小时表达为缺口，
 *       <b>而不是用 other 的值冒充</b>。</li>
 * </ul>
 */
@org.springframework.stereotype.Component
@org.springframework.context.annotation.DependsOn("metricConfig")
public class InMemoryMetricHourRank implements MetricHourRank {

    @lombok.Getter
    @lombok.EqualsAndHashCode
    @lombok.ToString
    private static class Group {
        private final String service;

        private final String metric;

        private final Instant hour;

        public Group(String service, String metric, Instant hour) {
            this.service = service;
            this.metric = metric;
            this.hour = hour;
        }
    }

    private final Map<Group, HourState> hours;

    public InMemoryMetricHourRank() {
        this.hours = new ConcurrentHashMap<>();
    }

    @Override
    public String record(String service, String metricName, String canonicalLabels, Instant eventTime) {
        HourState state = hours.computeIfAbsent(
                groupKey(service, metricName, hourOf(eventTime)), k -> new HourState());

        synchronized (state) {
            if (state.finalized) {
                return state.promoted.contains(canonicalLabels) ? canonicalLabels : SeriesKey.OTHER_LABELS;
            }
            state.counts.merge(canonicalLabels, 1L, Long::sum);
            if (state.promoted.contains(canonicalLabels)) {
                return canonicalLabels;
            }
            if (state.promoted.size() < MetricConfig.TOP_N) {
                state.promoted.add(canonicalLabels);
                return canonicalLabels;
            }
            return SeriesKey.OTHER_LABELS;
        }
    }
    @Override
    public Set<String> promotedLabels(String service, String metricName, Instant hourStart) {
        HourState state = hours.get(groupKey(service, metricName, hourOf(hourStart)));
        if (Objects.isNull(state)) {
            return Sets.newHashSet();
        }
        synchronized (state) {
            return Set.copyOf(state.promoted);
        }
    }
    @Override
    public boolean mergedIntoOther(String service, String metricName, String canonicalLabels, Instant hourStart) {
        HourState state = hours.get(groupKey(service, metricName, hourOf(hourStart)));
        if (Objects.isNull(state)) {
            return false;
        }
        synchronized (state) {
            if (!state.counts.containsKey(canonicalLabels)) {
                return false;
            }
            return !state.promoted.contains(canonicalLabels);
        }
    }
    @Override
    public void finalizeHour(Instant hourStart) {
        Instant hour = hourOf(hourStart);
        for (Map.Entry<Group, HourState> entry : hours.entrySet()) {
            Group key = entry.getKey();
            HourState state = entry.getValue();
            if (!Objects.equals(key.getHour(), hour)) {
                continue;
            }
            synchronized (state) {
                if (state.finalized) continue;
                state.promoted.clear();
                state.promoted.addAll(topLabels(state));
                state.finalized = true;
            }
        }
    }
    @Override
    public boolean finalized(Instant hourStart) {
        Instant hour = hourOf(hourStart);
        return hours.entrySet().stream()
                .filter(e -> Objects.equals(e.getKey().getHour(), hour))
                .findFirst()
                .map(e -> {
                    synchronized (e.getValue()) {
                        return e.getValue().finalized;
                    }
                })
                .orElse(false);
    }
    /**
     * 按上报次数降序取前 N；次数相同时按键名字典序，保证结果确定。
     */
    private Set<String> topLabels(HourState state) {
        return state.counts.entrySet().stream()
                .sorted(Map.Entry.<String, Long>comparingByValue().reversed()
                        .thenComparing(Map.Entry.comparingByKey()))
                .limit(Math.max(0, MetricConfig.TOP_N))
                .map(Map.Entry::getKey)
                .collect(LinkedHashSet::new, LinkedHashSet::add, LinkedHashSet::addAll);
    }
    private Group groupKey(String service, String metricName, Instant hour) {
        return new Group(service, metricName, hour);
    }
    private Instant hourOf(Instant instant) {
        return instant.truncatedTo(ChronoUnit.HOURS);
    }
    @Override
    public void clearBefore(Instant boundary) {
        hours.keySet().removeIf(key -> key.getHour().isBefore(boundary));
    }
    private static class HourState {
        final Map<String, Long> counts;

        final Set<String> promoted;

        boolean finalized;

        HourState() {
            this.counts = new LinkedHashMap<>();
            this.promoted = new LinkedHashSet<>();
        }
    }
}
