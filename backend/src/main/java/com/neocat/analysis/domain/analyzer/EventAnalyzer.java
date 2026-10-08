package com.neocat.analysis.domain.analyzer;

import com.neocat.analysis.domain.bucket.HourlyReportStore;
import com.neocat.analysis.domain.bucket.SeriesKey;
import com.neocat.analysis.domain.bucket.SeriesKind;
import com.neocat.analysis.domain.metric.MetricLabels;

import com.neocat.ingest.domain.tree.MessageTree;
import com.neocat.ingest.domain.tree.NodeKind;
import com.neocat.ingest.domain.tree.RawNode;

import java.time.Instant;
import java.util.Objects;
import org.springframework.modulith.NamedInterface;
import org.springframework.stereotype.Component;

/**
 * Event 分析器（PRD 02 §9、PRD 03 §8）。
 *
 * <p>Event 只产出次数类指标与 QPS：**不提供耗时、最小/最大/平均耗时、耗时分布和百分位**
 * （PRD 03 §8）。因此这里传入 {@code durationMs = 0}，使分布保持为空、耗时和为 0；
 * 查询层对 Event 请求分位会返回参数错误，从两端共同保证该口径不被违反。
 */
@NamedInterface("analysis")
@Component
public class EventAnalyzer implements Analyzer {

    private final HourlyReportStore store;

    public EventAnalyzer(HourlyReportStore store) {
        this.store = store;
    }
    @Override
    public String domain() {
        return "event";
    }
    @Override
    public void analyze(MessageTree tree) {
        for (RawNode node : tree.getNodes()) {
            if (!Objects.equals(node.getKind(), NodeKind.EVENT)) {
                continue;
            }
            Instant eventTime = Instant.ofEpochMilli(node.getTimestamp());
            String category = MetricLabels.categoryOf(node);
            boolean failure = !node.succeeded();

            // 事件没有耗时语义：走 addCountOnly，保持分布为空，
            // 使查询期对 Event 请求分位得到无值而非伪造的 0ms。
            store.addCountOnly(SeriesKey.of(tree.getServiceName(), SeriesKind.EVENT, category, node.getName()),
                    eventTime, failure);
            store.addCountOnly(SeriesKey.of(tree.getServiceName(), SeriesKind.EVENT, category, node.getName(),
                            tree.getInstanceId()),
                    eventTime, failure);
        }
    }
}
