package com.neocat.analysis.domain.dependency;

import com.neocat.analysis.domain.analyzer.Analyzer;
import com.neocat.analysis.domain.bucket.HourlyReportStore;
import com.neocat.analysis.domain.bucket.SeriesKey;
import com.neocat.analysis.domain.bucket.SeriesKind;

import com.neocat.ingest.domain.tree.MessageTree;
import com.neocat.ingest.domain.tree.NodeKind;
import com.neocat.ingest.domain.tree.RawNode;
import com.neocat.ingest.domain.tree.RemoteCallValue;

import java.time.Instant;
import java.util.Objects;

/**
 * 依赖分析器（PRD 02 §9、PRD 04 §6–7，链路 22）。
 *
 * <p>依赖边完全由**调用方树**驱动（PRD 04 §7）：
 * <ul>
 *   <li>调用方记录了远程调用 → 边产生，即使被调用方 MessageTree 从未到达；</li>
 *   <li>调用方可观测到的失败与耗时照常统计（下游内部细节不可见，只统计调用方视角）；</li>
 *   <li>只有「调用方关系本身未被观测到」才不产生边，因此本分析器不做任何下游存在性检查；</li>
 *   <li>自调用（下游 == 自身）不构成依赖边。</li>
 * </ul>
 *
 * <p>同时写入双向序列，使「下游列表」与「上游列表」都能独立查询：
 * <ul>
 *   <li>上游侧：{@code (upstream, DEPENDENCY, DOWNSTREAM, downstream)}</li>
 *   <li>下游侧：{@code (downstream, DEPENDENCY, UPSTREAM, upstream)}</li>
 * </ul>
 */
@org.springframework.modulith.NamedInterface("analysis")
@org.springframework.stereotype.Component
public class DependencyAnalyzer implements Analyzer {

    static final String DOWNSTREAM_DIRECTION = "DOWNSTREAM";

    static final String UPSTREAM_DIRECTION = "UPSTREAM";

    private final HourlyReportStore store;

    public DependencyAnalyzer(HourlyReportStore store) {
        this.store = store;
    }
    @Override
    public String domain() {
        return "dependency";
    }
    @Override
    public void analyze(MessageTree tree) {
        for (RawNode node : tree.getNodes()) {
            if (node.getKind() != NodeKind.REMOTE_CALL) {
                continue;
            }
            RemoteCallValue call = node.getRemoteCall();
            if (Objects.isNull(call) || Objects.isNull(call.getDownstreamService()) || call.getDownstreamService().isBlank()) {
                continue;
            }
            String upstream = tree.getServiceName();
            String downstream = call.getDownstreamService();
            if (Objects.isNull(upstream) || Objects.equals(upstream, downstream)) {
                // 自调用不是跨服务依赖
                continue;
            }
            Instant eventTime = Instant.ofEpochMilli(node.getTimestamp());
            boolean failure = !node.succeeded();

            // 上游视角：下游列表
            store.add(SeriesKey.of(upstream, SeriesKind.DEPENDENCY, DOWNSTREAM_DIRECTION, downstream),
                    eventTime, node.getDurationMs(), failure);
            // 下游视角：上游列表
            store.add(SeriesKey.of(downstream, SeriesKind.DEPENDENCY, UPSTREAM_DIRECTION, upstream),
                    eventTime, node.getDurationMs(), failure);
        }
    }
}
