package com.neocat.trace.infra.clickhouse;

import org.springframework.jdbc.core.JdbcTemplate;

import javax.sql.DataSource;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * 原始树与 Trace 关系的 JDBC 实现（技术方案 06 §5–6）。
 *
 * <p>SQL 全部参数化；表名与列名与 {@code 06-clickhouse-schema.sql} 一致。
 *
 * <p>关键点：{@code nc_raw_tree} 与 {@code nc_trace_relation} 各有独立 TTL。
 * ClickHouse 的 TTL 负责自动清理树本体，而关系表留存更久，
 * 因此「曾收到但已过期」可以被识别为 {@code EXPIRED} 而不是 {@code MISSING}。
 */
public class JdbcRawTreeQuery implements RawTreeQuery {

    private final JdbcTemplate jdbc;

    public JdbcRawTreeQuery(DataSource dataSource) {
        this.jdbc = new JdbcTemplate(dataSource);
    }
    @Override
    public void insertTree(TraceTreeRow row) {
        jdbc.update("""
                INSERT INTO neocat.nc_raw_tree
                  (service, instance, message_id, root_message_id, parent_message_id,
                   tree_timestamp, fingerprint, payload)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                """,
                row.getService(), row.getInstance(), row.getMessageId(), row.getRootMessageId(),
                row.getParentMessageId(), java.sql.Timestamp.from(row.getTreeTimestamp()),
                row.getFingerprint(), row.getPayload());
    }
    @Override
    public void insertRelation(TraceRelationRow row) {
        jdbc.update("""
                INSERT INTO neocat.nc_trace_relation
                  (message_id, root_message_id, parent_message_id, service, instance, tree_timestamp)
                VALUES (?, ?, ?, ?, ?, ?)
                """,
                row.getMessageId(), row.getRootMessageId(), row.getParentMessageId(),
                row.getService(), row.getInstance(), java.sql.Timestamp.from(row.getTreeTimestamp()));
    }
    @Override
    public TraceTreeRow selectTree(String messageId) {
        List<TraceTreeRow> rows = jdbc.query("""
                SELECT service, instance, message_id, root_message_id, parent_message_id,
                       tree_timestamp, fingerprint, payload
                FROM neocat.nc_raw_tree
                WHERE message_id = ?
                ORDER BY tree_timestamp DESC
                LIMIT 1
                """, TREE_ROW_MAPPER, messageId);
        return rows.isEmpty() ? null : rows.get(0);
    }
    @Override
    public List<TraceTreeRow> selectTreesByRoot(String rootMessageId) {
        return jdbc.query("""
                SELECT service, instance, message_id, root_message_id, parent_message_id,
                       tree_timestamp, fingerprint, payload
                FROM neocat.nc_raw_tree
                WHERE root_message_id = ?
                ORDER BY tree_timestamp
                """, TREE_ROW_MAPPER, rootMessageId);
    }
    @Override
    public List<TraceTreeRow> selectTreesByServiceAndRange(String service, Instant from, Instant to) {
        return jdbc.query("""
                SELECT service, instance, message_id, root_message_id, parent_message_id,
                       tree_timestamp, fingerprint, payload
                FROM neocat.nc_raw_tree
                WHERE service = ? AND tree_timestamp >= ? AND tree_timestamp < ?
                ORDER BY tree_timestamp DESC
                """, TREE_ROW_MAPPER, service,
                java.sql.Timestamp.from(from), java.sql.Timestamp.from(to));
    }
    @Override
    public boolean existsRelation(String messageId) {
        Integer count = jdbc.queryForObject("""
                SELECT count() FROM neocat.nc_trace_relation WHERE message_id = ?
                """, Integer.class, messageId);
        return count != null && count > 0;
    }
    @Override
    public TraceRelationRow selectRelation(String messageId) {
        List<TraceRelationRow> rows = jdbc.query("""
                SELECT message_id, root_message_id, parent_message_id, service, instance, tree_timestamp
                FROM neocat.nc_trace_relation
                WHERE message_id = ?
                ORDER BY tree_timestamp DESC
                LIMIT 1
                """, RELATION_ROW_MAPPER, messageId);
        return rows.isEmpty() ? null : rows.get(0);
    }
    @Override
    public List<TraceRelationRow> selectRelationsByRoot(String rootMessageId) {
        return jdbc.query("""
                SELECT message_id, root_message_id, parent_message_id, service, instance, tree_timestamp
                FROM neocat.nc_trace_relation
                WHERE root_message_id = ?
                ORDER BY tree_timestamp
                """, RELATION_ROW_MAPPER, rootMessageId);
    }
    /**
     * 删除超期树本体，返回被删除的 messageId。
     *
     * <p>ClickHouse 的 {@code ALTER TABLE ... DELETE} 是异步变更，
     * 因此这里先查出待删除集合再执行删除，使调用方能得到确定的结果列表。
     * 关系表**不删除**。
     */
    @Override
    public List<String> deleteTreesOlderThan(Instant threshold) {
        List<String> ids = jdbc.queryForList("""
                SELECT message_id FROM neocat.nc_raw_tree WHERE tree_timestamp < ?
                """, String.class, java.sql.Timestamp.from(threshold));

        if (ids.isEmpty()) {
            return List.of();
        }
        jdbc.update("ALTER TABLE neocat.nc_raw_tree DELETE WHERE tree_timestamp < ?",
                java.sql.Timestamp.from(threshold));
        return ids;
    }

    // ── 行映射 ───────────────────────────────────────────────

    private static final org.springframework.jdbc.core.RowMapper<TraceTreeRow> TREE_ROW_MAPPER =
            (ResultSet rs, int rowNum) -> new TraceTreeRow(
                    rs.getString("service"),
                    rs.getString("instance"),
                    rs.getString("message_id"),
                    rs.getString("root_message_id"),
                    rs.getString("parent_message_id"),
                    rs.getTimestamp("tree_timestamp").toInstant(),
                    rs.getString("fingerprint"),
                    rs.getString("payload"));

    private static final org.springframework.jdbc.core.RowMapper<TraceRelationRow> RELATION_ROW_MAPPER =
            (ResultSet rs, int rowNum) -> new TraceRelationRow(
                    rs.getString("message_id"),
                    rs.getString("root_message_id"),
                    rs.getString("parent_message_id"),
                    rs.getString("service"),
                    rs.getString("instance"),
                    rs.getTimestamp("tree_timestamp").toInstant());
}
