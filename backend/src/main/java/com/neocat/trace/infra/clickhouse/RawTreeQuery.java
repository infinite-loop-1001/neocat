package com.neocat.trace.infra.clickhouse;

import com.neocat.trace.domain.tree.TraceNode;
import com.neocat.trace.domain.tree.TraceRelation;

import java.time.Instant;
import java.util.List;

import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.ToString;
import org.springframework.lang.Nullable;

/**
 * 原始树与 Trace 关系的存储查询（技术方案 06 §5–6）。
 *
 * <p>把 SQL 与行映射集中在此，使 {@link ClickHouseRawTreeStore} 只负责装配，
 * 转换规则可离线验证（不需要真实 ClickHouse）。
 *
 * <p>两类数据的留存期不同：
 * <ul>
 *   <li>{@code nc_raw_tree} 7 天 TTL —— 树本体；</li>
 *   <li>{@code nc_trace_relation} 用于判定「曾收到但过期」，
 *       因此 {@link #everExisted} 在树被清理后仍可为真。</li>
 * </ul>
 */
public interface RawTreeQuery {

    void insertTree(TraceTreeRow row);

    void insertRelation(TraceRelationRow row);

    @Nullable
    TraceTreeRow selectTree(String messageId);

    List<TraceTreeRow> selectTreesByRoot(String rootMessageId);

    List<TraceTreeRow> selectTreesByServiceAndRange(String service, Instant from, Instant to);

    /** 关系索引查询：不受树清理影响。 */
    boolean existsRelation(String messageId);

    @Nullable
    TraceRelationRow selectRelation(String messageId);

    List<TraceRelationRow> selectRelationsByRoot(String rootMessageId);

    /** 删除超过阈值的树本体，返回被删除的 messageId。 */
    List<String> deleteTreesOlderThan(Instant threshold);

    /**
     * 原始树行（{@code nc_raw_tree}）。
     *
     * @param payload 序列化后的树内容；由 {@link TreePayloadCodec} 编解码
     */
    @Getter
    @EqualsAndHashCode
    @ToString
    class TraceTreeRow {
        private final String service;

        private final String instance;

        private final String messageId;

        private final String rootMessageId;

        private final String parentMessageId;

        private final Instant treeTimestamp;

        private final String fingerprint;

        private final String payload;

        public TraceTreeRow(String service, String instance, String messageId, String rootMessageId, String parentMessageId, Instant treeTimestamp, String fingerprint, String payload) {
            this.service = service;
            this.instance = instance;
            this.messageId = messageId;
            this.rootMessageId = rootMessageId;
            this.parentMessageId = parentMessageId;
            this.treeTimestamp = treeTimestamp;
            this.fingerprint = fingerprint;
            this.payload = payload;
        }

    }
    /**
     * Trace 关系行（{@code nc_trace_relation}）。
     */
    @Getter
    @EqualsAndHashCode
    @ToString
    class TraceRelationRow {
        private final String messageId;

        private final String rootMessageId;

        private final String parentMessageId;

        private final String service;

        private final String instance;

        private final Instant treeTimestamp;

        public TraceRelationRow(String messageId, String rootMessageId, String parentMessageId, String service, String instance, Instant treeTimestamp) {
            this.messageId = messageId;
            this.rootMessageId = rootMessageId;
            this.parentMessageId = parentMessageId;
            this.service = service;
            this.instance = instance;
            this.treeTimestamp = treeTimestamp;
        }

    }
    /** 便捷转换：关系行 → 领域对象。 */
    static TraceRelation toDomain(TraceRelationRow row) {
        return new TraceRelation(row.getMessageId(), row.getRootMessageId(), row.getParentMessageId(),
                row.getService(), row.getInstance(), row.getTreeTimestamp().toEpochMilli());
    }
    /**
     * 树内容编解码（技术方案 06 §5：{@code payload} 列）。
     *
     * <p>实现可用 JSON 或 Protobuf；选择 Protobuf 可复用上报协议中的节点结构，
     * 避免为存储再定义一套模型。
     */
    interface TreePayloadCodec {

        String encode(List<TraceNode> nodes);

        List<TraceNode> decode(String payload);
    }
}