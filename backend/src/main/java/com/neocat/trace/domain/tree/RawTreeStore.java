package com.neocat.trace.domain.tree;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * 原始 MessageTree 与 Trace 关系存储（PRD 02 §10、技术方案 06 §5–6）。
 *
 * <p>由 ClickHouse 实现；领域单测使用 Mock/Stub 隔离外部存储。
 * 两类数据的留存期可以不同：关系索引用于识别「曾收到但已过期」。
 */
@org.springframework.modulith.NamedInterface("trace")
public interface RawTreeStore {

    /** 保存一棵树，同时写入其关系记录。 */
    void save(TraceTree tree);

    @org.springframework.lang.Nullable
    TraceTree findByMessageId(String messageId);

    /** 按 root 取同一 Trace 下仍在留存期内的全部树。 */
    List<TraceTree> findByRootMessageId(String rootMessageId);

    /** 该 messageId 是否曾经被写入过（关系索引判断，不受树清理影响）。 */
    boolean everExisted(String messageId);

    /** 该 messageId 的关系记录；用于在树缺失时给出服务名等展示信息。 */
    @org.springframework.lang.Nullable
    TraceRelation relationOf(String messageId);

    /** 同一 Trace 的全部关系记录（含树已被清理的）。 */
    List<TraceRelation> relationsByRoot(String rootMessageId);

    /** 该树的写入指纹；未存过返回空。 */
    @org.springframework.lang.Nullable
    String fingerprintOf(String messageId);

    /**
     * 按服务与时间范围查找候选树，用于取样。
     *
     * @param service 服务名
     * @param from    起点（含，epoch millis）
     * @param to      终点（不含，epoch millis）
     */
    List<TraceTree> findByServiceAndTimeRange(String service, long from, long to);

    /** 清理超过留存期的原始树（保留关系记录）。 */
    List<String> evictTreesOlderThan(Instant threshold);
}
