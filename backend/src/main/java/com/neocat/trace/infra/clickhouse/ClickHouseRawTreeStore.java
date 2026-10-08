package com.neocat.trace.infra.clickhouse;

import com.neocat.trace.domain.tree.RawTreeStore;
import com.neocat.trace.domain.tree.TraceRelation;
import com.neocat.trace.domain.tree.TraceTree;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import org.springframework.stereotype.Repository;

/**
 * 基于 ClickHouse 的原始树存储（技术方案 06 §5–6、02 §7）。
 *
 * <p>关键语义：
 * <ul>
 *   <li>保存树时**同时写入关系索引**。关系不受 7 天 TTL 影响，
 *       因此 {@link #everExisted} 可区分「曾收到但已过期」与「从未收到」；</li>
 *   <li>{@link #evictTreesOlderThan} 只删树本体、保留关系 ——
 *       这正是 {@code TraceAssembler} 判定 {@code EXPIRED} 依赖的前提；</li>
 *   <li>{@link #fingerprintOf} 供 ingest 的幂等兜底查询使用（7 天内精确）。</li>
 * </ul>
 */
@Repository
public class ClickHouseRawTreeStore implements RawTreeStore {

    private final RawTreeQuery query;

    private final RawTreeQuery.TreePayloadCodec codec;

    public ClickHouseRawTreeStore(RawTreeQuery query, RawTreeQuery.TreePayloadCodec codec) {
        this.query = query;
        this.codec = codec;
    }
    @Override
    public void save(TraceTree tree) {
        query.insertTree(new RawTreeQuery.TraceTreeRow(
                tree.getServiceName(),
                tree.getInstanceId(),
                tree.getMessageId(),
                tree.getRootMessageId(),
                Objects.isNull(tree.getParentMessageId()) ? "" : tree.getParentMessageId(),
                Instant.ofEpochMilli(tree.getTreeTimestamp()),
                tree.getFingerprint(),
                codec.encode(tree.getNodes())));

        query.insertRelation(new RawTreeQuery.TraceRelationRow(
                tree.getMessageId(),
                tree.getRootMessageId(),
                Objects.isNull(tree.getParentMessageId()) ? "" : tree.getParentMessageId(),
                tree.getServiceName(),
                tree.getInstanceId(),
                Instant.ofEpochMilli(tree.getTreeTimestamp())));
    }
    @Override
    public TraceTree findByMessageId(String messageId) {
        var row = query.selectTree(messageId);
        return Objects.isNull(row) ? null : toDomain(row);
    }
    @Override
    public List<TraceTree> findByRootMessageId(String rootMessageId) {
        return query.selectTreesByRoot(rootMessageId).stream().map(this::toDomain).toList();
    }
    @Override
    public boolean everExisted(String messageId) {
        return query.existsRelation(messageId);
    }
    @Override
    public TraceRelation relationOf(String messageId) {
        var row = query.selectRelation(messageId);
        return Objects.isNull(row) ? null : RawTreeQuery.toDomain(row);
    }
    @Override
    public List<TraceRelation> relationsByRoot(String rootMessageId) {
        return query.selectRelationsByRoot(rootMessageId).stream()
                .map(RawTreeQuery::toDomain)
                .toList();
    }
    @Override
    public String fingerprintOf(String messageId) {
        var row = query.selectTree(messageId);
        return Objects.isNull(row) ? null : row.getFingerprint();
    }
    @Override
    public List<TraceTree> findByServiceAndTimeRange(String service, long from, long to) {
        return query.selectTreesByServiceAndRange(service,
                        Instant.ofEpochMilli(from), Instant.ofEpochMilli(to)).stream()
                .map(this::toDomain)
                .toList();
    }
    @Override
    public List<String> evictTreesOlderThan(Instant threshold) {
        // 只删树本体：关系索引保留，使「曾收到但过期」仍可被识别
        return new ArrayList<>(query.deleteTreesOlderThan(threshold));
    }
    private TraceTree toDomain(RawTreeQuery.TraceTreeRow row) {
        return new TraceTree(
                row.getMessageId(),
                row.getRootMessageId(),
                row.getParentMessageId(),
                row.getService(),
                row.getInstance(),
                row.getTreeTimestamp().toEpochMilli(),
                row.getFingerprint(),
                codec.decode(row.getPayload()));
    }
}
