package com.neocat.analysis.domain.analyzer;

import com.neocat.analysis.domain.bucket.HourlyReportStore;
import com.neocat.analysis.domain.bucket.SeriesKey;
import com.neocat.analysis.domain.bucket.SeriesKind;
import com.neocat.analysis.domain.metric.MetricLabels;

import com.neocat.ingest.domain.tree.MessageTree;
import com.neocat.ingest.domain.tree.NodeKind;
import com.neocat.ingest.domain.tree.RawNode;

import java.time.Instant;

/**
 * Transaction 分析器（PRD 02 §9、PRD 03 §7）。
 *
 * <p>行为：
 * <ul>
 *   <li>只处理 {@link NodeKind#TRANSACTION} 节点；</li>
 *   <li>同时写入「全机器聚合行」（instance = {@code all}）与「实例行」，两者独立累加，
 *       查询期再由公共分母换算 QPS（PRD 03 §4：多机器先合并总次数再除公共分母）；</li>
 *   <li>桶归属使用**节点事件时间**（PRD 03 §2.3、PRD 02 §7），不使用树时间；</li>
 *   <li>非成功状态计入 failCount，但耗时照常进入耗时统计。</li>
 * </ul>
 */
@org.springframework.modulith.NamedInterface("analysis")
@org.springframework.stereotype.Component
public class TransactionAnalyzer implements Analyzer {

    private final HourlyReportStore store;

    public TransactionAnalyzer(HourlyReportStore store) {
        this.store = store;
    }
    @Override
    public String domain() {
        return "transaction";
    }
    @Override
    public void analyze(MessageTree tree) {
        for (RawNode node : tree.getNodes()) {
            if (node.getKind() != NodeKind.TRANSACTION) {
                continue;
            }
            Instant eventTime = Instant.ofEpochMilli(node.getTimestamp());
            String category = MetricLabels.categoryOf(node);
            boolean failure = !node.succeeded();

            store.add(SeriesKey.of(tree.getServiceName(), SeriesKind.TRANSACTION, category, node.getName()), eventTime,
                    node.getDurationMs(), failure);
            store.add(SeriesKey.of(tree.getServiceName(), SeriesKind.TRANSACTION, category, node.getName(),
                            tree.getInstanceId()), eventTime,
                    node.getDurationMs(), failure);
        }
    }
}
