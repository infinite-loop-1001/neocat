package com.neocat.trace.domain.sample;

import com.neocat.trace.domain.tree.RawTreeStore;
import com.neocat.trace.domain.tree.TraceNode;
import com.neocat.trace.domain.tree.TraceTree;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.ToString;
import org.springframework.modulith.NamedInterface;
import org.springframework.stereotype.Service;

/**
 * 取样查询（PRD 03 §11、PRD 00 §12）。
 *
 * <p>规则：
 * <ul>
 *   <li>按事件时间**严格倒序**返回最近 N 条（默认 30）。
 *       不做故障优先排序：更早的异常调用不会被提到最新顺序之前（PRD 03 §11 明确要求）；</li>
 *   <li>受当前服务、时间范围、实例、Type/Name/Problem 筛选约束；</li>
 *   <li>汇总仍存在但原始树超过留存期时，条目照常返回，但 {@code traceAvailable = false}，
 *       前端据此禁用下钻（PRD 00 §6「Trace 过期」）。</li>
 * </ul>
 */
@NamedInterface("trace")
@Service
public class SampleService {

    private final RawTreeStore store;

    public SampleService(RawTreeStore store) {
        this.store = store;
    }
    public List<Sample> samples(SampleQuery query, Instant now, Duration retention) {
        int limit = query.getLimit() <= 0 ? SampleQuery.DEFAULT_LIMIT : query.getLimit();
        Instant expiryThreshold = now.minus(retention);

        List<Candidate> candidates = new ArrayList<>();
        for (TraceTree tree : store.findByServiceAndTimeRange(query.getService(), query.getFrom(), query.getTo())) {
            for (TraceNode node : tree.getNodes()) {
                if (!query.matches(tree, node)) {
                    continue;
                }
                candidates.add(new Candidate(tree, node));
            }
        }

        return candidates.stream()
                .sorted(Comparator.comparingLong((Candidate c) -> c.getNode().getTimestamp()).reversed()
                        .thenComparing(c -> c.getTree().getMessageId()))
                .limit(limit)
                .map(c -> new Sample(
                        c.getTree().getMessageId(),
                        c.getNode().getTimestamp(),
                        c.getNode().getDurationMs(),
                        c.getNode().getStatus(),
                        c.getNode().getName(),
                        !Instant.ofEpochMilli(c.getNode().getTimestamp()).isBefore(expiryThreshold)))
                .toList();
    }
    @Getter
    @EqualsAndHashCode
    @ToString
    private static class Candidate {
        private final TraceTree tree;

        private final TraceNode node;

        public Candidate(TraceTree tree, TraceNode node) {
            this.tree = tree;
            this.node = node;
        }

    }
}
