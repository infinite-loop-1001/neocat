package com.neocat.analysis.infra.jdbc;

import com.neocat.analysis.domain.bucket.AggregatedRow;
import com.neocat.analysis.domain.bucket.AggregationLevel;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.Objects;

/**
 * ClickHouse 桶写入（技术方案 06 §1–3、§9）。
 *
 * <p>写入策略：append 版本化快照，不做数据库 upsert：
 * <ul>
 *   <li>同名 (key, bucket) 允许重复（桶表不是 ReplacingMergeTree）；</li>
 *   <li>读取先按 source/key/bucket 选最新版本，再跨来源 sum 和合并分布；</li>
 *   <li>只对旧 source 为空的增量行直接 sum，不把重写快照重复统计。</li>
 * </ul>
 *
 * <p>TTL 由表定义（{@code 06-clickhouse-schema.sql}）负责，
 * 此处的清理方法用于在需要时显式提前清理（例如手动运维）。
 */
@org.springframework.stereotype.Component
public class JdbcReportBucketSink implements com.neocat.analysis.domain.bucket.ReportBucketSinkPort {

    private final org.springframework.jdbc.core.JdbcTemplate jdbc;

    private final String snapshotSource;

    private final java.util.concurrent.atomic.AtomicLong snapshotVersion;

    private final java.util.concurrent.atomic.AtomicLong rollupVersion;

    private final java.util.function.Supplier<java.time.ZoneId> zone;

    public JdbcReportBucketSink(javax.sql.DataSource dataSource) {
        this(dataSource, () -> java.time.ZoneId.of("UTC"));
    }
    @org.springframework.beans.factory.annotation.Autowired
    public JdbcReportBucketSink(@org.springframework.beans.factory.annotation.Qualifier("clickHouseDataSource")
                               javax.sql.DataSource dataSource, java.util.function.Supplier<java.time.ZoneId> zone) {
        this.jdbc = new org.springframework.jdbc.core.JdbcTemplate(dataSource);
        this.zone = zone;
        this.snapshotSource = java.util.UUID.randomUUID().toString();
        this.snapshotVersion = new java.util.concurrent.atomic.AtomicLong();
        this.rollupVersion = new java.util.concurrent.atomic.AtomicLong();
    }
    @Override
    public void writeMinuteBuckets(List<AggregatedRow> rows) {
        write("nc_minute_bucket", "minute", rows);
    }
    @Override
    public void writeHourBuckets(List<AggregatedRow> rows) {
        write("nc_hour_bucket", "hour", rows);
    }
    @Override
    public void writeDayBuckets(List<AggregatedRow> rows) {
        write("nc_day_bucket", "day", rows);
    }
    @Override
    public void writeWeekBuckets(List<AggregatedRow> rows) {
        write("nc_week_bucket", "week_start", rows);
    }
    @Override
    public void writeMonthBuckets(List<AggregatedRow> rows) {
        write("nc_month_bucket", "month_start", rows);
    }
    /**
     * 批量写入。分布以 {@code long[]} 直接映射到 ClickHouse 的 {@code Array(UInt64)}。
     */
    private void write(String table, String bucketColumn, List<AggregatedRow> rows) {
        if (Objects.isNull(rows) || rows.isEmpty()) {
            return;
        }
        String sql = """
                INSERT INTO neocat.%s
                  (service, kind, type, name, instance, problem_category, metric_labels,
                   %s, count, fail_count, duration_sum, duration_min, duration_max,
                    value_sum, value_count, distribution, covered_seconds, value_last, value_last_time,
                     snapshot_source, snapshot_version, value_count_missing)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """.formatted(table, bucketColumn);

        long version = snapshotVersion.incrementAndGet();
        long globalVersion = rollupVersion.updateAndGet(previous -> Math.max(previous + 1, System.currentTimeMillis()));
        List<Object[]> batch = rows.stream().map(row -> new Object[]{
                row.key().getService(),
                row.key().getKind().name(),
                Objects.isNull(row.key().getType()) ? "" : row.key().getType(),
                Objects.isNull(row.key().getName()) ? "" : row.key().getName(),
                row.key().getInstance(),
                Objects.isNull(row.key().getProblemCategory()) ? "" : row.key().getProblemCategory(),
                Objects.isNull(row.key().getMetricLabels()) ? "" : row.key().getMetricLabels(),
                row.level() == AggregationLevel.DAY || row.level() == AggregationLevel.WEEK || row.level() == AggregationLevel.MONTH
                        ? java.sql.Date.valueOf(row.bucketStart().atZone(zone.get()).toLocalDate()) : java.sql.Timestamp.from(row.bucketStart()),
                row.count(),
                row.failCount(),
                row.durationSum(),
                row.durationMin(),
                row.durationMax(),
                row.valueSum(),
                row.valueCount(),
                toArrayLiteral(row.distribution().segments()),
                 row.coveredSeconds(), row.valueLast(),
                 Objects.isNull(row.valueLastTime()) ? null : java.sql.Timestamp.from(row.valueLastTime()),
                 row.level() == AggregationLevel.MINUTE || row.level() == AggregationLevel.HOUR ? snapshotSource : "global-rollup-v1",
                 row.level() == AggregationLevel.MINUTE || row.level() == AggregationLevel.HOUR ? version : globalVersion,
                 row.valueCountMissing() || row.key().getKind() == com.neocat.analysis.domain.bucket.SeriesKind.METRIC && row.valueCount() == 0 ? 1 : 0
        }).toList();

        jdbc.batchUpdate(sql, batch);
    }
    /**
     * 分布数组 → ClickHouse 数组字面量。
     *
     * <p>用字面量而非 JDBC 数组，避免不同驱动对 {@code Array(UInt64)} 的支持差异。
     */
    static String toArrayLiteral(long[] segments) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < segments.length; i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append(segments[i]);
        }
        return sb.append(']').toString();
    }
    @Override
    public List<AggregatedRow> readBuckets(AggregationLevel level, Instant from, Instant to) {
        String table = tableOf(level);
        String bucketColumn = bucketColumnOf(level);
        String sql = """
                SELECT service, kind, type, name, instance, problem_category, metric_labels,
                       %s AS bucket_start,
                       sum(count) AS total_count, sum(fail_count) AS total_fail,
                       sum(duration_sum) AS total_duration,
                       min(duration_min) AS min_duration, max(duration_max) AS max_duration,
                        sum(value_sum) AS total_value, sum(value_count) AS total_value_count,
                        max(value_count_missing) AS count_missing,
                         sumForEach(distribution) AS merged_distribution,
                         argMax(value_last, tuple(value_last_time, value_last)) AS last_value,
                         max(value_last_time) AS last_sample_time
                FROM %s
                WHERE %s >= ? AND %s < ?
                GROUP BY service, kind, type, name, instance, problem_category, metric_labels, bucket_start
                ORDER BY bucket_start
                """.formatted(bucketColumn, com.neocat.analysis.domain.schedule.ReportSnapshotSql.source(table, bucketColumn,
                        bucketColumn + " >= ? AND " + bucketColumn + " < ?"), bucketColumn, bucketColumn);

        boolean dateBucket = level == AggregationLevel.DAY || level == AggregationLevel.WEEK || level == AggregationLevel.MONTH;
        Object lower = dateBucket ? java.sql.Date.valueOf(from.atZone(zone.get()).toLocalDate()) : java.sql.Timestamp.from(from);
        Object upper = dateBucket ? java.sql.Date.valueOf(to.atZone(zone.get()).toLocalDate()) : java.sql.Timestamp.from(to);
        return jdbc.query(sql, new BucketRowMapper(level, zone.get()), lower, upper, lower, upper, lower, upper);
    }
    @Override
    public long evictMinuteBucketsBefore(Instant threshold) {
        return evict("nc_minute_bucket", "minute", threshold);
    }
    @Override
    public long evictHourBucketsBefore(Instant threshold) {
        return evict("nc_hour_bucket", "hour", threshold);
    }
    @Override
    public long evictLongTermBucketsBefore(Instant threshold) {
        return evict("nc_day_bucket", "day", threshold)
                + evict("nc_week_bucket", "week_start", threshold)
                + evict("nc_month_bucket", "month_start", threshold);
    }
    private long evict(String table, String bucketColumn, Instant threshold) {
        Integer count = jdbc.queryForObject(
                "SELECT count() FROM neocat.%s WHERE %s < ?".formatted(table, bucketColumn),
                Integer.class, java.sql.Timestamp.from(threshold));
        if (Objects.isNull(count) || count == 0) {
            return 0;
        }
        jdbc.update("ALTER TABLE neocat.%s DELETE WHERE %s < ?".formatted(table, bucketColumn),
                java.sql.Timestamp.from(threshold));
        return count;
    }
    private static String tableOf(AggregationLevel level) {
        return switch (level) {
            case MINUTE -> "nc_minute_bucket";
            case HOUR -> "nc_hour_bucket";
            case DAY -> "nc_day_bucket";
            case WEEK -> "nc_week_bucket";
            case MONTH -> "nc_month_bucket";
        };
    }
    private static String bucketColumnOf(AggregationLevel level) {
        return switch (level) {
            case MINUTE -> "minute";
            case HOUR -> "hour";
            case DAY -> "day";
            case WEEK -> "week_start";
            case MONTH -> "month_start";
        };
    }
    /**
     * 桶行映射。
     *
     * <p>只读取原始分量（分子与分布），**不在 SQL 里算 avg / 分位**，
     * 保证上层「合并后重算」的不变式不被绕过。
     */
    private static class BucketRowMapper
            implements org.springframework.jdbc.core.RowMapper<AggregatedRow> {

        private final AggregationLevel level;

        private final java.time.ZoneId zone;

        BucketRowMapper(AggregationLevel level, java.time.ZoneId zone) {
            this.level = level;
            this.zone = zone;
        }

        @Override
        public AggregatedRow mapRow(ResultSet rs, int rowNum) throws SQLException {
            var key = new com.neocat.analysis.domain.bucket.SeriesKey(
                    rs.getString("service"),
                    com.neocat.analysis.domain.bucket.SeriesKind.valueOf(
                            rs.getString("kind").toUpperCase(java.util.Locale.ROOT)),
                    rs.getString("type"),
                    rs.getString("name"),
                    rs.getString("instance"),
                    rs.getString("problem_category"),
                    rs.getString("metric_labels"));

            Instant start = level == AggregationLevel.DAY || level == AggregationLevel.WEEK || level == AggregationLevel.MONTH
                    ? rs.getObject("bucket_start", java.time.LocalDate.class).atStartOfDay(zone).toInstant()
                    : rs.getTimestamp("bucket_start").toInstant();
            AggregatedRow row = new AggregatedRow(key, start, level,
                    // 上层层级的覆盖秒数由桶长决定，避免依赖明细行的 covered_seconds
                    switch (level) {
                        case MINUTE -> 60L;
                        case HOUR -> 3600L;
                        case DAY -> 86400L;
                        case WEEK -> 7 * 86400L;
                        case MONTH -> 30 * 86400L;
                    });
            row.addCount(rs.getLong("total_count"), rs.getLong("total_fail"),
                    rs.getLong("total_duration"), rs.getLong("min_duration"),
                    rs.getLong("max_duration"));
            row.addValue(rs.getDouble("total_value"), rs.getLong("total_value_count"));
            row.markValueCountMissing(rs.getBoolean("count_missing"));
            java.sql.Timestamp lastTime = rs.getTimestamp("last_sample_time");
            row.mergeLastValue((Double) rs.getObject("last_value"), Objects.isNull(lastTime) ? null : lastTime.toInstant());
            row.setDistribution(com.neocat.analysis.domain.bucket.DurationDistribution
                    .fromSegments(distributionOf(rs)));
            return row;
        }

        private long[] distributionOf(ResultSet rs) throws SQLException {
            Object raw = rs.getObject("merged_distribution");
            long[] segments = new long[16];
            Object content = raw instanceof java.sql.Array array ? array.getArray() : raw;
            if (content instanceof Object[] values) {
                for (int i = 0; i < Math.min(values.length, segments.length); i++) {
                    segments[i] = values[i] instanceof Number number ? number.longValue() : 0L;
                }
            }
            return segments;
        }
    }
}

