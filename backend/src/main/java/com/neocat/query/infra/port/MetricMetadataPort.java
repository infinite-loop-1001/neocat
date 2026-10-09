package com.neocat.query.infra.port;

import java.time.Instant;
import java.util.List;
import com.neocat.analysis.domain.metric.Entry;

public interface MetricMetadataPort {
    List<Entry> entries(String service, String metric, Instant from, Instant to);
}
