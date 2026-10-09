package com.neocat.analysis.domain.metric;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import org.springframework.modulith.NamedInterface;
import com.neocat.analysis.domain.metric.metadata.Entry;

/**
 * Actual ingest-time ownership, not a later ranking that could contradict written buckets.
 */
@NamedInterface("analysis")
public interface MetricLabelMetadata {

    void record(String service, String metric, Map<String, String> labels, String owner, Instant time);

    List<Entry> entries(Instant from, Instant to);

    void clearBefore(Instant boundary);
}