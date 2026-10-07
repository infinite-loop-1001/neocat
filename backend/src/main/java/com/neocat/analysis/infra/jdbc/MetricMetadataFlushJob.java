package com.neocat.analysis.infra.jdbc;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.neocat.analysis.domain.metric.MetricLabelMetadata;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.jdbc.core.JdbcTemplate;
import java.time.*;
import java.util.*;
import com.neocat.common.config.IngestConfig;

@Component
@org.springframework.context.annotation.DependsOn("ingestConfig")
public class MetricMetadataFlushJob {
    private final MetricLabelMetadata memory;

    private final JdbcTemplate jdbc;

    private final ObjectMapper json;

    private final Clock clock;

    private final com.neocat.analysis.domain.metric.MetricHourRank rank;

    public MetricMetadataFlushJob(MetricLabelMetadata memory, @Qualifier("clickHouseDataSource") javax.sql.DataSource source,
                                  ObjectMapper json, Clock clock, com.neocat.analysis.domain.metric.MetricHourRank rank) {
        this.memory = memory; this.jdbc = new JdbcTemplate(source); this.json = json; this.clock = clock;
        this.rank = rank;
    }
    @Scheduled(cron = "10 * * * * *")
    public void flush() {
        Instant now = clock.instant();
        var entries = memory.entries(Instant.EPOCH, now.plusSeconds(3600));
        if (entries.isEmpty()) return;
        List<Object[]> rows = entries.stream().map(e -> {
            try { return new Object[] { e.getService(), e.getMetric(), java.sql.Timestamp.from(e.getHour()), e.getCanonicalLabels(),
                    json.writeValueAsString(e.getLabels()), e.isMerged() ? 1 : 0, e.getVersion(), e.getSource() }; }
            catch (Exception ex) { throw new IllegalStateException("Metric metadata serialization failed", ex); }
        }).toList();
        jdbc.batchUpdate("""
                INSERT INTO neocat.nc_metric_label_metadata (service, metric_name, hour, labels, labels_json, merged, version, source)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                """, rows);
        // Keep completed hours available through the normal hourly rollup grace period.
        Instant boundary = now.minusSeconds(Math.max(2, IngestConfig.ACCEPT_LATE_HOURS) * 3600L)
                .truncatedTo(java.time.temporal.ChronoUnit.HOURS);
        memory.clearBefore(boundary);
        rank.clearBefore(boundary);
    }
}


