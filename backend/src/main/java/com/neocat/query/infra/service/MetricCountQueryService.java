package com.neocat.query.infra.service;

import com.neocat.common.time.clock.TimeProvider;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.collect.Lists;
import com.neocat.common.error.*;
import com.neocat.common.error.exception.*;
import com.neocat.common.time.bucket.*;
import com.neocat.common.time.range.*;
import com.neocat.query.domain.metric.*;
import com.neocat.query.domain.report.*;
import com.neocat.query.infra.port.*;
import java.time.*;
import java.util.*;
import java.util.function.Supplier;
import org.springframework.stereotype.Service;
import com.neocat.analysis.domain.metric.metadata.Entry;
import com.neocat.query.domain.report.result.ResolvedRange;

@Service
public class MetricCountQueryService {
    private final ReportDataPort data;

    private final MetricMetadataPort metadata;

    private final RangeResolver ranges;

    private final Supplier<ZoneId> zone;

    private final ObjectMapper json;

    public MetricCountQueryService(ReportDataPort data, MetricMetadataPort metadata, TimeBucketResolver buckets,
                                 Supplier<ZoneId> zone, ObjectMapper json) {
        this.data = data;
        this.metadata = metadata;
        this.ranges = new RangeResolver(buckets);
        this.zone = zone;
        this.json = json;
    }
    private ResolvedRange range(String raw) {
        return ranges.resolve(RangeParams.parse(raw, TimeProvider.now()), zone.get());
    }
    public List<Map<String, String>> metrics(String service, String range) {
        var window = range(range);
        return data.typesOf("METRIC", service, window.getFrom(), window.getTo()).stream().distinct().sorted()
                .map(name -> Map.of("name", name)).toList();
    }
    public List<Map<String, Object>> labels(String service, String metric, String range) {
        var window = range(range);
        requireMetric(service, metric, window);
        Map<String, Set<String>> values = new TreeMap<>();
        for (var entry : metadata.entries(service, metric, window.getFrom(), window.getTo())) {
            entry.getLabels().forEach((key, value) -> values.computeIfAbsent(key, k -> new TreeSet<>()).add(value));
        }
        return values.entrySet().stream().map(entry -> {
            Map<String, Object> label = new LinkedHashMap<>();
            label.put("key", entry.getKey());
            label.put("values", List.copyOf(entry.getValue()));
            return label;
        }).toList();
    }
    public Map<String, Object> count(String service, String metric, String range, String filters) {
        MetricFilters conditions = MetricFilters.parse(filters, json);
        var window = range(range);
        requireMetric(service, metric, window);
        // One level per source interval; preserves label identity and never sums multiple rollup levels.
        var rows = data.metricSourceRows(service, metric, window.getFrom(), window.getTo(),
                Granularity.fromSeconds(window.bucketSeconds()));
        var entries = conditions.total() ? Lists.<Entry>newArrayList()
                : metadata.entries(service, metric, window.getFrom(), window.getTo());
        Instant now = TimeProvider.now();
        var points = new MetricCountService().points(rows, entries, window.getBuckets(), conditions, now,
                bucket -> data.droppedBetween("METRIC", service, metric, null, bucket.getStart(),
                        bucket.getEnd().isAfter(now) ? now : bucket.getEnd()));
        return Map.of("metric", metric, "bucketSeconds", window.bucketSeconds(), "points", points);
    }
    private void requireMetric(String service, String metric, ResolvedRange window) {
        if (!data.typesOf("METRIC", service, window.getFrom(), window.getTo()).contains(metric))
            throw new ResourceNotFoundException(ErrorCode.NOT_FOUND, "Metric " + metric);
    }
}
