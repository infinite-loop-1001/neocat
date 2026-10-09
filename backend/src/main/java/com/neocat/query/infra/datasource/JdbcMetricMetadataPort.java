package com.neocat.query.infra.datasource;

import com.neocat.query.infra.port.MetricMetadataPort;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.neocat.analysis.domain.metric.MetricLabelMetadata;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.ToString;
import org.springframework.jdbc.core.JdbcTemplate;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.*;
import java.util.Objects;
import java.sql.SQLException;
import java.time.temporal.ChronoUnit;
import javax.sql.DataSource;
import com.neocat.analysis.domain.metric.metadata.Entry;

/** Versioned snapshots: merge flags are monotone; never sum a re-written metadata snapshot. */
public  class JdbcMetricMetadataPort implements MetricMetadataPort {
    private final JdbcTemplate jdbc;

    private final ObjectMapper json;

    private final MetricLabelMetadata memory;

    public JdbcMetricMetadataPort(DataSource source, ObjectMapper json, MetricLabelMetadata memory) {
        this.jdbc = new JdbcTemplate(source); this.json = json; this.memory = memory;
    }
    @Override
    public List<Entry> entries(String service, String metric, Instant from, Instant to) {
        List<Entry> result = new ArrayList<>(jdbc.query("""
                SELECT service, metric_name, hour, labels, source, any(labels_json) AS decoded_labels,
                       max(merged) AS is_merged, max(version) AS report_count
                FROM neocat.nc_metric_label_metadata
                WHERE service = ? AND metric_name = ? AND hour < ? AND hour >= ?
                GROUP BY service, metric_name, hour, labels, source
                """, (rs, index) -> {
            Map<String, String> labels;
            try { labels = json.readValue(rs.getString("decoded_labels"), new TypeReference<Map<String, String>>() {}); }
            catch (Exception e) { throw new SQLException("Invalid persisted Metric label metadata", e); }
            return new Entry(rs.getString("service"), rs.getString("metric_name"),
                    rs.getTimestamp("hour").toInstant(), rs.getString("labels"), labels,
                    rs.getBoolean("is_merged"), rs.getLong("report_count"), rs.getString("source"));
        }, service, metric, Timestamp.from(to), Timestamp.from(from.truncatedTo(ChronoUnit.HOURS))));
        result.addAll(memory.entries(from, to).stream().filter(e -> Objects.equals(e.getService(), service) && Objects.equals(e.getMetric(), metric)).toList());

        @Getter
        @EqualsAndHashCode
        @ToString
        class Key {
            private final Instant hour;

            private final String labels;

            private final String source;

            public Key(Instant hour, String labels, String source) {
                this.hour = hour;
                this.labels = labels;
                this.source = source;
            }
        }
        Map<Key, Entry> deduplicated = new LinkedHashMap<>();
        for (var entry : result) {
            deduplicated.merge(new Key(entry.getHour(), entry.getCanonicalLabels(), entry.getSource()), entry, (a, b) ->
                    new Entry(a.getService(), a.getMetric(), a.getHour(), a.getCanonicalLabels(), a.getLabels(),
                            a.isMerged() || b.isMerged(), Math.max(a.getVersion(), b.getVersion()), a.getSource()));
        }
        return List.copyOf(deduplicated.values());
    }
}
