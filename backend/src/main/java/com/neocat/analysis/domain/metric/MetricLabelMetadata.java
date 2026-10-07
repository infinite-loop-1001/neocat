package com.neocat.analysis.domain.metric;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/** Actual ingest-time ownership, not a later ranking that could contradict written buckets. */
@org.springframework.modulith.NamedInterface("analysis")
public interface MetricLabelMetadata {

    @org.springframework.modulith.NamedInterface("analysis")
    @lombok.Getter
    @lombok.EqualsAndHashCode
    @lombok.ToString
    class Entry {
        private final String service;

        private final String metric;

        private final Instant hour;

        private final String canonicalLabels;

        private final Map<String, String> labels;

        private final boolean merged;

        private final long version;

        private final String source;

        public Entry(
                String service, String metric, Instant hour,
                String canonicalLabels, Map<String, String> labels,
                boolean merged, long version, String source
        ) {
            this.service = service;
            this.metric = metric;
            this.hour = hour;
            this.canonicalLabels = canonicalLabels;
            this.labels = labels;
            this.merged = merged;
            this.version = version;
            this.source = source;
        }

        public Entry(String service, String metric, Instant hour, String canonicalLabels,
                     Map<String, String> labels, boolean merged, long version) {
            this(service, metric, hour, canonicalLabels, labels, merged, version, "");
        }
    }
    void record(String service, String metric, Map<String, String> labels, String owner, Instant time);
    List<Entry> entries(Instant from, Instant to);
    void clearBefore(Instant boundary);
}





