package com.neocat.analysis.infra.store;

import com.neocat.analysis.domain.analyzer.*;
import com.neocat.analysis.domain.bucket.*;
import com.neocat.analysis.domain.dependency.*;
import com.neocat.analysis.domain.metric.*;
import com.neocat.analysis.domain.schedule.*;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

@Component
public  class InMemoryMetricLabelMetadata implements MetricLabelMetadata {
    @lombok.Getter
    @lombok.EqualsAndHashCode
    @lombok.ToString
    private static class Key {
        private final String service;

        private final String metric;

        private final Instant hour;

        private final String labels;

        public Key(String service, String metric, Instant hour, String labels) {
            this.service = service;
            this.metric = metric;
            this.hour = hour;
            this.labels = labels;
        }
 }
    private final Map<Key, Entry> entries;

    private final String source;

    public InMemoryMetricLabelMetadata() {
        this.entries = new ConcurrentHashMap<>();
        this.source = UUID.randomUUID().toString();
    }

    @Override
    public void record(String service, String metric, Map<String, String> labels, String owner, Instant time) {
        Map<String, String> copy = labels == null ? Map.of() : Map.copyOf(labels);
        String canonical = MetricLabels.canonicalize(copy);
        Instant hour = time.truncatedTo(ChronoUnit.HOURS);
        var key = new Key(service, metric, hour, canonical);
        entries.compute(key, (k, old) -> new Entry(service, metric, hour, canonical, copy,
                SeriesKey.OTHER_LABELS.equals(owner) || old != null && old.isMerged(),
                old == null ? 1 : old.getVersion() + 1, source));
    }
    @Override
    public List<Entry> entries(Instant from, Instant to) {
        return entries.values().stream().filter(e -> e.getHour().isBefore(to) && e.getHour().plusSeconds(3600).isAfter(from)).toList();
    }
    @Override
    public void clearBefore(Instant boundary) { entries.keySet().removeIf(k -> k.getHour().isBefore(boundary)); }
}

