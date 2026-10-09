package com.neocat.query.domain.metric;

import com.neocat.query.domain.series.Quality;
import com.neocat.analysis.domain.analyzer.*;
import com.neocat.analysis.domain.bucket.*;
import com.neocat.analysis.domain.dependency.*;
import com.neocat.analysis.domain.metric.*;
import com.neocat.analysis.domain.schedule.*;
import com.neocat.common.time.bucket.Bucket;
import java.time.*;
import java.util.*;
import org.apache.commons.collections4.CollectionUtils;
import java.util.Objects;
import java.time.temporal.ChronoUnit;
import java.util.function.Predicate;
import org.springframework.modulith.NamedInterface;
import com.neocat.analysis.domain.metric.Entry;

/** Exact counts from observation rows, preserving identity and uncertainty across source hours. */
@NamedInterface("query")
public class MetricCountService {
    public List<Map<String, Object>> points(List<AggregatedRow> rows, List<Entry> metadata,
                                          List<Bucket> buckets, MetricFilters filters, Instant now,
                                          Predicate<Bucket> dropped) {
        List<Map<String, Object>> result = new ArrayList<>();
        for (Bucket bucket : buckets) {
            var sources = rows.stream().filter(r -> bucket.contains(r.bucketStart())).toList();
            boolean future = !bucket.getStart().isBefore(now);
            boolean countMissing = sources.stream().anyMatch(r -> r.valueCountMissing() || r.valueCount() == 0);
            boolean unknown = !filters.total() && metadata.stream().anyMatch(e -> e.isMerged()
                    && e.getHour().isBefore(bucket.getEnd()) && e.getHour().plusSeconds(3600).isAfter(bucket.getStart())
                    && filters.matches(e.getLabels()));
            long count = 0;
            for (var row : sources) {
                if (filters.total()) { count += row.valueCount(); continue; }
                Instant sourceHour = Objects.equals(row.level(), AggregationLevel.DAY) ? row.bucketStart()
                        : row.bucketStart().truncatedTo(ChronoUnit.HOURS);
                Instant sourceEnd = Objects.equals(row.level(), AggregationLevel.DAY) ? sourceHour.plusSeconds(86400) : sourceHour.plusSeconds(3600);
                var entries = metadata.stream().filter(e -> e.getHour().plusSeconds(3600).isAfter(sourceHour) && e.getHour().isBefore(sourceEnd)).toList();
                if (CollectionUtils.isEmpty(entries)) { unknown = true; continue; }
                if (Objects.equals(SeriesKey.OTHER_LABELS, row.key().getMetricLabels())) {
                    // A metadata flush can lag behind the bucket flush. Do not interpret
                    // an incomplete list of merged combinations as exhaustive ownership.
                    long recordedMerged = entries.stream().filter(Entry::isMerged).mapToLong(Entry::getVersion).sum();
                    if (recordedMerged < row.valueCount()
                            || entries.stream().anyMatch(e -> e.isMerged() && filters.matches(e.getLabels()))) unknown = true;
                } else {
                    var labels = entries.stream().filter(e -> Objects.equals(e.getCanonicalLabels(), row.key().getMetricLabels())).findFirst();
                    if (labels.isEmpty()) unknown = true;
                    else if (filters.matches(labels.get().getLabels())) count += row.valueCount();
                }
            }
            boolean realtime = bucket.contains(now);
            Quality quality = future ? Quality.NO_DATA : dropped.test(bucket) ? Quality.DROPPED
                    : countMissing ? Quality.NO_DATA : unknown ? Quality.MERGED_OTHER : sources.stream().noneMatch(r -> r.valueCount() > 0) ? Quality.NO_DATA
                    : realtime ? Quality.REALTIME : bucket.isPartial() ? Quality.PARTIAL
                    : count == 0 ? Quality.ZERO : Quality.OK;
            Map<String, Object> point = new LinkedHashMap<>();
            point.put("bucketStart", bucket.getStart().toEpochMilli()); point.put("bucketEnd", bucket.getEnd().toEpochMilli());
            point.put("value", quality.gap() ? null : count); point.put("quality", quality.name());
            point.put("coveredSeconds", Math.max(0, Math.min(bucket.getCoveredSeconds(), Duration.between(bucket.getStart(), now).getSeconds())));
            point.put("realtime", realtime && !future); point.put("partial", bucket.isPartial());
            result.add(point);
        }
        return result;
    }
}
