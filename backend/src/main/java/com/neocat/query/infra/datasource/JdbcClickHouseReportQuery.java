package com.neocat.query.infra.datasource;

import com.neocat.analysis.domain.bucket.AggregationLevel;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

import javax.sql.DataSource;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.Objects;

import com.neocat.analysis.domain.schedule.ReportSnapshotSql;

import java.sql.Array;
import java.sql.Date;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.function.Supplier;

/**
 * ClickHouse 报表查询的 JDBC 实现（技术方案 06 §10）。
 *
 * <p>SQL 全部参数化；表名与列名与 {@code 06-clickhouse-schema.sql} 一致。
 *
 * <p>关键 SQL 约定：
 * <ul>
 *   <li>同名 (key, bucket) 可重复写入（不用 ReplacingMergeTree），
 *       查询期先按 source/version 去重快照，再 sum 分子、sumForEach 逐段相加分布；</li>
 *   <li>**不做分位计算**：分位在应用层由合并后的分布重算，
 *       避免「平均子桶分位」这一错误（PRD 03 §3）；</li>
 *   <li>{@code covered_seconds} 用 {@code sum()} 之外的聚合会破坏部分覆盖语义，
 *       因此按桶取 {@code max()}（同一桶的所有写入覆盖秒数相同）。</li>
 * </ul>
 */
public class JdbcClickHouseReportQuery implements ClickHouseReportQuery {

    private final JdbcTemplate jdbc;

    private final Supplier<ZoneId> zone;

    public JdbcClickHouseReportQuery(DataSource dataSource) {
        this(dataSource, () -> ZoneId.of("UTC"));
    }

    public JdbcClickHouseReportQuery(DataSource dataSource,
                                     Supplier<ZoneId> zone) {
        this.jdbc = new JdbcTemplate(dataSource);
        this.zone = zone;
    }

    @Override
    public List<BucketRow> minuteRows(String service, String kind, String type, String name,
                                      String instance, Instant from, Instant to) {
        return queryBuckets("nc_minute_bucket", "minute", AggregationLevel.MINUTE,
                service, kind, type, name, instance, from, to);
    }

    @Override
    public List<BucketRow> hourRows(String service, String kind, String type, String name,
                                    String instance, Instant from, Instant to) {
        return queryBuckets("nc_hour_bucket", "hour", AggregationLevel.HOUR,
                service, kind, type, name, instance, from, to);
    }

    @Override
    public List<BucketRow> dayRows(String service, String kind, String type, String name,
                                   String instance, Instant from, Instant to) {
        return queryBuckets("nc_day_bucket", "day", AggregationLevel.DAY,
                service, kind, type, name, instance, from, to);
    }

    /**
     * 通用桶查询：按 (服务, 类型, 分类, 名称, 实例, 桶) 分组，
     * 合并分子与分布数组，再回传原始分量（不在 SQL 里算 avg / 分位）。
     */
    private List<BucketRow> queryBuckets(String table, String bucketColumn, AggregationLevel level,
                                         String service, String kind, String type, String name,
                                         String instance, Instant from, Instant to) {
        StringBuilder sql = new StringBuilder("""
                SELECT service, kind, type, name, instance, problem_category, metric_labels,
                       %s AS bucket_start,
                       sum(count)        AS total_count,
                       sum(fail_count)   AS total_fail,
                       sum(duration_sum) AS total_duration,
                       min(duration_min) AS min_duration,
                       max(duration_max) AS max_duration,
                       sum(value_sum)    AS total_value,
                       sum(value_count)  AS total_value_count,
                       max(value_count_missing) AS count_missing,
                       %s AS merged_distribution,
                       max(covered_seconds) AS covered_seconds,
                       argMax(value_last, tuple(value_last_time, value_last)) AS last_value,
                       max(value_last_time) AS last_sample_time
                FROM %s
                WHERE service = ? AND kind = ? AND %s >= ? AND %s < ?
                """.formatted(
                bucketColumn,
                "sumForEach(distribution)",
                ReportSnapshotSql.source(table, bucketColumn,
                        "service = ? AND kind = ? AND " + bucketColumn + " >= ? AND " + bucketColumn + " < ?"), bucketColumn, bucketColumn));

        List<Object> args = new ArrayList<>();
        args.add(service);
        args.add(kind);
        Object lower = Objects.equals(level, AggregationLevel.DAY) ? Date.valueOf(from.atZone(zone.get()).toLocalDate()) : Timestamp.from(from);
        Object upper = Objects.equals(level, AggregationLevel.DAY) ? Date.valueOf(to.atZone(zone.get()).toLocalDate()) : Timestamp.from(to);
        args.add(lower);
        args.add(upper);
        // Snapshot branches are filtered before GROUP BY, avoiding a scan of all retained services.
        args.addAll(new ArrayList<>(args));
        args.addAll(List.of(service, kind, lower, upper));

        if (Objects.nonNull(type)) {
            sql.append(" AND type = ?");
            args.add(type);
        }
        if (Objects.nonNull(name)) {
            sql.append("METRIC".equalsIgnoreCase(kind) ? " AND metric_labels = ?" : " AND name = ?");
            args.add(name);
        }
        sql.append(" AND instance = ?");
        args.add(instance);

        sql.append("""
                GROUP BY service, kind, type, name, instance, problem_category, metric_labels, bucket_start
                ORDER BY bucket_start
                """);

        return jdbc.query(sql.toString(), new BucketRowMapper(level, zone.get()), args.toArray());
    }

    @Override
    public List<String> distinctInstances(String service, String kind, Instant from, Instant to) {
        return jdbc.queryForList("""
                        SELECT DISTINCT instance
                        FROM (
                          SELECT instance FROM neocat.nc_minute_bucket
                          WHERE service = ? AND kind = ? AND minute >= ? AND minute < ? AND instance != 'all'
                          UNION ALL
                          SELECT instance FROM neocat.nc_hour_bucket
                          WHERE service = ? AND kind = ? AND hour >= ? AND hour < ? AND instance != 'all'
                          UNION ALL
                          SELECT instance FROM neocat.nc_day_bucket
                          WHERE service = ? AND kind = ? AND day >= ? AND day < ? AND instance != 'all'
                        )
                        ORDER BY instance
                        """, String.class,
                service, kind, Timestamp.from(from), Timestamp.from(to),
                service, kind, Timestamp.from(from), Timestamp.from(to),
                service, kind, Date.valueOf(from.atZone(zone.get()).toLocalDate()),
                Date.valueOf(to.atZone(zone.get()).toLocalDate()));
    }

    @Override
    public List<String> distinctTypes(String service, String kind, Instant from, Instant to) {
        return jdbc.queryForList("""
                        SELECT DISTINCT type
                        FROM (
                          SELECT type FROM neocat.nc_minute_bucket
                          WHERE service = ? AND kind = ? AND minute >= ? AND minute < ?
                          UNION ALL
                          SELECT type FROM neocat.nc_hour_bucket
                          WHERE service = ? AND kind = ? AND hour >= ? AND hour < ?
                          UNION ALL
                          SELECT type FROM neocat.nc_day_bucket
                          WHERE service = ? AND kind = ? AND day >= ? AND day < ?
                        )
                        ORDER BY type
                        """, String.class,
                service, kind, Timestamp.from(from), Timestamp.from(to),
                service, kind, Timestamp.from(from), Timestamp.from(to),
                service, kind, Date.valueOf(from.atZone(zone.get()).toLocalDate()),
                Date.valueOf(to.atZone(zone.get()).toLocalDate()));
    }

    @Override
    public List<String> distinctNames(String service, String kind, String type, Instant from, Instant to) {
        String nameColumn = "METRIC".equalsIgnoreCase(kind) ? "metric_labels" : "name";
        StringBuilder sql = new StringBuilder("""
                SELECT DISTINCT name
                FROM (
                  SELECT type, %s AS name FROM neocat.nc_minute_bucket
                  WHERE service = ? AND kind = ? AND minute >= ? AND minute < ?
                  UNION ALL
                  SELECT type, %s AS name FROM neocat.nc_hour_bucket
                  WHERE service = ? AND kind = ? AND hour >= ? AND hour < ?
                  UNION ALL
                  SELECT type, %s AS name FROM neocat.nc_day_bucket
                  WHERE service = ? AND kind = ? AND day >= ? AND day < ?
                )
                """.formatted(nameColumn, nameColumn, nameColumn));
        List<Object> args = new ArrayList<>(List.of(
                service, kind, Timestamp.from(from), Timestamp.from(to),
                service, kind, Timestamp.from(from), Timestamp.from(to),
                service, kind, Date.valueOf(from.atZone(zone.get()).toLocalDate()),
                Date.valueOf(to.atZone(zone.get()).toLocalDate())));
        if (Objects.nonNull(type)) {
            sql.append(" WHERE type = ?");
            args.add(type);
        }
        sql.append(" ORDER BY name");
        return jdbc.queryForList(sql.toString(), String.class, args.toArray());
    }

    /**
     * 该桶是否存在队列满丢弃事件（PRD 00 §6：丢弃必须显示为缺口而非零）。
     */
    @Override
    public boolean hasDropEvent(String service, String kind, String type, String name, Instant bucketStart) {
        return hasDropEvents(service, bucketStart, bucketStart.plusSeconds(60));
    }

    @Override
    public boolean hasDropEvents(String service, Instant from, Instant to) {
        Integer count = jdbc.queryForObject("""
                        SELECT count()
                        FROM neocat.nc_quality_event
                        WHERE event_type = 'QUEUE_FULL'
                          AND service = ?
                          AND event_time >= ? AND event_time < ?
                        """, Integer.class,
                service,
                Timestamp.from(from), Timestamp.from(to));
        return Objects.nonNull(count) && count > 0;
    }

    /**
     * 某 Metric 具体序列在某小时是否被并入 other（PRD 04 §3：该小时显示缺口，
     * **不得用 other 值冒充**）。
     */
    @Override
    public boolean mergedIntoOther(String service, String metricName, String labels, Instant hourStart) {
        Integer count = jdbc.queryForObject("""
                        SELECT count()
                        FROM neocat.nc_metric_label_metadata
                        WHERE service = ? AND metric_name = ? AND labels = ?
                          AND hour = ? AND merged = 1
                        """, Integer.class,
                service, metricName, labels,
                Timestamp.from(hourStart.truncatedTo(ChronoUnit.HOURS)));
        return count > 0;
    }

    /**
     * 桶行映射：把分布数组还原为 long[]，其余列直接读出。
     */
    private static class BucketRowMapper implements RowMapper<ClickHouseReportQuery.BucketRow> {

        private final AggregationLevel level;

        private final ZoneId zone;

        BucketRowMapper(AggregationLevel level, ZoneId zone) {
            this.level = level;
            this.zone = zone;
        }

        @Override
        public ClickHouseReportQuery.BucketRow mapRow(ResultSet rs, int rowNum) throws SQLException {
            return new ClickHouseReportQuery.BucketRow(
                    rs.getString("service"),
                    rs.getString("kind"),
                    rs.getString("type"),
                    rs.getString("name"),
                    rs.getString("instance"),
                    rs.getString("problem_category"),
                    rs.getString("metric_labels"),
                    bucketStartOf(rs),
                    level,
                    rs.getLong("total_count"),
                    rs.getLong("total_fail"),
                    rs.getLong("total_duration"),
                    rs.getLong("min_duration"),
                    rs.getLong("max_duration"),
                    rs.getBigDecimal("total_value"),
                    rs.getLong("total_value_count"),
                    distributionOf(rs),
                    rs.getLong("covered_seconds"),
                    rs.getBigDecimal("last_value"),
                    Objects.isNull(rs.getTimestamp("last_sample_time")) ? null : rs.getTimestamp("last_sample_time").toInstant(),
                    rs.getBoolean("count_missing"));
        }

        /**
         * 读桶起点。
         *
         * <p>日桶表的 `day` 是 ClickHouse {@code Date}，没有时刻语义。
         * 直接 {@code getTimestamp().toInstant()} 会按驱动时区解释，在 UTC+8 下
         * 把 2026-10-01 读成 2026-09-30T16:00Z，桶起点随即落到前一天 —— 按天取数
         * 的横轴会整体左移一天。因此日粒度按**平台时区当地 00:00** 还原。
         */
        private Instant bucketStartOf(ResultSet rs) throws SQLException {
            Timestamp raw = rs.getTimestamp("bucket_start");
            if (Objects.isNull(raw)) {
                return null;
            }
            if (Objects.equals(level, AggregationLevel.DAY) || Objects.equals(level, AggregationLevel.WEEK)
                    || Objects.equals(level, AggregationLevel.MONTH)) {
                LocalDate date = rs.getObject("bucket_start", LocalDate.class);
                if (Objects.nonNull(date)) {
                    return date.atStartOfDay(zone).toInstant();
                }
            }
            return raw.toInstant();
        }

        /**
         * 读取合并后的 16 段分布。
         *
         * <p>以 {@code Object[]} 接收，兼容不同 JDBC 驱动把 ClickHouse Array
         * 暴露为数组或 {@code java.sql.Array} 的差异。
         */
        private long[] distributionOf(ResultSet rs) throws SQLException {
            Object raw = rs.getObject("merged_distribution");
            long[] segments = new long[16];
            if (raw instanceof Object[] values) {
                for (int i = 0; i < Math.min(values.length, segments.length); i++) {
                    segments[i] = toLong(values[i]);
                }
                return segments;
            }
            if (raw instanceof Array array) {
                Object content = array.getArray();
                if (content instanceof Object[] values) {
                    for (int i = 0; i < Math.min(values.length, segments.length); i++) {
                        segments[i] = toLong(values[i]);
                    }
                }
            }
            return segments;
        }

        private long toLong(Object value) {
            return value instanceof Number number ? number.longValue() : 0L;
        }
    }
}
