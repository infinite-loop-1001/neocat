package com.neocat.trace.domain.tree;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Objects;

/**
 * Trace 组装（PRD 02 §10、技术方案 02 §7）。
 *
 * <p>组装流程：
 * <pre>
 * 1. 按 messageId 取种子树；取不到时用关系索引判断「过期」还是「从未收到」
 * 2. 按 rootMessageId 取同一 Trace 的可用树
 * 3. 按 parentMessageId 连接成树
 * 4. 对「调用方已记录远程调用、但对应树不可用」的子服务补上缺失/过期节点
 *     —— 这是 PRD 02 §10「已知父子关系但子树未收到：显示缺失节点」的实现方式
 * 5. 展开树内节点为 spans
 * </pre>
 *
 * <p>关键设计：缺失节点来源是**调用方的 RemoteCall 节点**，而不是关系索引。
 * 关系索引只用来区分「曾收到但过期」与「从未收到」这两种不可用状态。
 */
@org.springframework.modulith.NamedInterface("trace")
@org.springframework.stereotype.Service
public class TraceAssembler {

    private final RawTreeStore store;

    public TraceAssembler(RawTreeStore store) {
        this.store = store;
    }
    /**
     * 组装结果。
     *
     * @param root    根节点；树完全不可用时为 null
     * @param expired 种子树已过期（曾收到但超期）
     * @param missing 种子树从未收到
     */
    @org.springframework.modulith.NamedInterface("trace")
    @lombok.Getter
    @lombok.EqualsAndHashCode
    @lombok.ToString
    public static class AssemblyResult {
        private final TraceTreeNode root;

        private final boolean expired;

        private final boolean missing;

        public AssemblyResult(TraceTreeNode root, boolean expired, boolean missing) {
            this.root = root;
            this.expired = expired;
            this.missing = missing;
        }


        public boolean usable() {
            return Objects.nonNull(root);
        }
    }
    public AssemblyResult assemble(String messageId, Instant now, Duration retention) {
        TraceTree seedTree = store.findByMessageId(messageId);
        if (java.util.Objects.isNull(seedTree)) {
            return store.everExisted(messageId)
                    ? new AssemblyResult(null, true, false)
                    : new AssemblyResult(null, false, true);
        }

        if (isExpired(seedTree.getTreeTimestamp(), now, retention)) {
            return new AssemblyResult(null, true, false);
        }

        String rootId = Objects.isNull(seedTree.getRootMessageId()) ? seedTree.getMessageId() : seedTree.getRootMessageId();
        List<TraceTree> inTrace = new ArrayList<>(store.findByRootMessageId(rootId));
        if (inTrace.stream().noneMatch(t -> t.getMessageId().equals(seedTree.getMessageId()))) {
            inTrace.add(seedTree);
        }
        inTrace.sort(Comparator.comparingLong(TraceTree::getTreeTimestamp));

        Map<String, TraceTreeNode> byMessageId = new LinkedHashMap<>();
        for (TraceTree tree : inTrace) {
            byMessageId.put(tree.getMessageId(), nodeOf(tree, NodeAvailability.PRESENT, null));
        }
        for (TraceTree tree : inTrace) {
            TraceTreeNode node = byMessageId.get(tree.getMessageId());
            if (tree.hasParent() && byMessageId.containsKey(tree.getParentMessageId())) {
                byMessageId.get(tree.getParentMessageId()).addChild(node);
            }
        }

        // 用调用方的 RemoteCall 节点补齐缺失/过期子服务
        for (TraceTree tree : inTrace) {
            TraceTreeNode parent = byMessageId.get(tree.getMessageId());
            for (TraceNode span : parent.spans()) {
                if (!"CALL".equals(span.getCategory())) {
                    continue;
                }
                String downstreamService = span.getName();
                if (Objects.isNull(downstreamService) || downstreamService.isBlank()) {
                    continue;
                }
                boolean childPresent = inTrace.stream()
                        .anyMatch(t -> downstreamService.equals(t.getServiceName())
                                && tree.getMessageId().equals(t.getParentMessageId()));
                if (childPresent) {
                    continue;
                }
                parent.addChild(placeholderFor(downstreamService, rootId, tree, span, now, retention));
            }
        }

        TraceTreeNode root = byMessageId.get(rootId);
        if (Objects.isNull(root)) {
            root = byMessageId.get(seedTree.getMessageId());
        }
        return new AssemblyResult(root, false, false);
    }
    /**
     * 为「调用方已记录但子树不可用」的子服务构造占位节点。
     *
     * <p>优先按关系索引判断是过期还是从未收到：关系索引记录过该 (root, downstream) 组合，
     * 说明曾收到，只是原始树被清理。
     */
    private TraceTreeNode placeholderFor(String downstreamService, String rootId, TraceTree parentTree,
                                         TraceNode span, Instant now, Duration retention) {
        List<TraceRelation> relations = store.relationsByRoot(rootId);
        Optional<TraceRelation> expiredRelation = relations.stream()
                .filter(r -> downstreamService.equals(r.getServiceName()))
                .filter(r -> parentTree.getMessageId().equals(r.getParentMessageId()))
                .findFirst();

        if (expiredRelation.isPresent()) {
            TraceRelation relation = expiredRelation.get();
            return new TraceTreeNode(relation.getMessageId(), relation.getServiceName(), relation.getInstanceId(),
                    relation.getTreeTimestamp(), NodeAvailability.EXPIRED,
                    "该子树已超过留存期，原始树不可用");
        }
        long approxTimestamp = span.getTimestamp();
        return new TraceTreeNode("missing:" + downstreamService + ":" + parentTree.getMessageId(),
                downstreamService, null, approxTimestamp, NodeAvailability.MISSING,
                "调用方已记录该下游调用，但未收到其 MessageTree");
    }
    private TraceTreeNode nodeOf(TraceTree tree, NodeAvailability availability, String reason) {
        TraceTreeNode node = new TraceTreeNode(tree.getMessageId(), tree.getServiceName(), tree.getInstanceId(),
                tree.getTreeTimestamp(), availability, reason);
        tree.getNodes().forEach(node::addSpan);
        return node;
    }
    private boolean isExpired(long treeTimestamp, Instant now, Duration retention) {
        Instant at = Instant.ofEpochMilli(treeTimestamp);
        return at.isBefore(now.minus(retention));
    }
}
