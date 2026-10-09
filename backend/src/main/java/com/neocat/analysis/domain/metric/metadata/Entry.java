package com.neocat.analysis.domain.metric.metadata;

import java.time.Instant;
import java.util.Map;

import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.ToString;
import org.springframework.modulith.NamedInterface;

@NamedInterface("analysis")
@Getter
@EqualsAndHashCode
@ToString
public class Entry {
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
