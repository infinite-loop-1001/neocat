package com.neocat.analysis.domain.schedule;

import java.util.Objects;

/** Read upgraded snapshot writes once per writer/key/bucket; legacy append rows remain additive. */
@org.springframework.modulith.NamedInterface("analysis")
public class ReportSnapshotSql {
    private ReportSnapshotSql() { }
    public static String source(String table, String bucket) {
        return source(table, bucket, "1 = 1");
    }
    public static String source(String table, String bucket, String predicate) {
        String keys = "service, kind, type, name, instance, problem_category, metric_labels, " + bucket;
        String[] columns = {"count", "fail_count", "duration_sum", "duration_min", "duration_max",
                "value_sum", "value_count", "distribution", "covered_seconds", "value_last", "value_last_time", "value_count_missing"};
        String tuple = "tuple(" + String.join(", ", columns) + ")";
        StringBuilder selected = new StringBuilder(keys);
        for (int i = 0; i < columns.length; i++) selected.append(", tupleElement(latest, ").append(i + 1).append(") AS ").append(columns[i]);
        // Global rollups rebuild the full lower-level history. Once such a snapshot exists,
        // legacy additive upper-level rows must not be counted on top of it during upgrade.
        String legacyExclusion = Objects.equals(table, "nc_minute_bucket") || Objects.equals(table, "nc_hour_bucket") ? ""
                : " AND tuple(" + keys + ") NOT IN (SELECT " + keys + " FROM neocat." + table
                + " WHERE snapshot_source = 'global-rollup-v1')";
        return "(SELECT " + keys + ", " + String.join(", ", columns) + " FROM neocat." + table
                + " WHERE snapshot_source = '' AND " + predicate + legacyExclusion + " UNION ALL SELECT " + selected
                + " FROM (SELECT " + keys + ", snapshot_source, argMax(" + tuple + ", snapshot_version) AS latest"
                + " FROM neocat." + table + " WHERE snapshot_source != '' AND " + predicate + " GROUP BY " + keys + ", snapshot_source))";
    }
}
