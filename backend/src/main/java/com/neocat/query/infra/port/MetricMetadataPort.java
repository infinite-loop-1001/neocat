package com.neocat.query.infra.port;

import com.neocat.analysis.domain.metric.MetricLabelMetadata;
import java.time.Instant;
import java.util.List;

public interface MetricMetadataPort {
    List<MetricLabelMetadata.Entry> entries(String service, String metric, Instant from, Instant to);
}
