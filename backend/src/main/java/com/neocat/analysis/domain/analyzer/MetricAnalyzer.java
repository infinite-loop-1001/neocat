package com.neocat.analysis.domain.analyzer;

import com.neocat.analysis.domain.bucket.HourlyReportStore;
import com.neocat.analysis.domain.bucket.SeriesKey;
import com.neocat.analysis.domain.metric.MetricHourRank;
import com.neocat.analysis.domain.metric.MetricLabelMetadata;
import com.neocat.analysis.domain.metric.MetricLabels;

import com.neocat.ingest.domain.tree.MessageTree;
import com.neocat.ingest.domain.tree.MetricValue;
import com.neocat.ingest.domain.tree.NodeKind;
import com.neocat.ingest.domain.tree.RawNode;

import java.time.Instant;
import java.util.Objects;

/**
 * Metric 分析器（PRD 04 §1–4，链路 21）。
 *
 * <p>处理逻辑：
 * <pre>
 * 对每个 Metric 节点：
 *   1. 规范化标签 → 序列身份
 *   2. 向 MetricHourRank 登记本次上报，取回归属标签（真实序列 或 other）
 *   3. 以归属标签构造序列键，写入分钟桶（数值统计 + 分布）
 * </pre>
 *
 * <p>要点：
 * <ul>
 *   <li>**Top1000 限制不拒绝上报**：超出名额的组合只是并入 other，数据仍然入库（PRD 04 §2）；</li>
 *   <li>`other` 是可独立查看、画趋势、计算分位和环比的序列，因此这里正常写桶，
 *       不把它标记为异常；</li>
 *   <li>不使用 {@link SeriesKey#ALL} 之外的聚合：Metric 只按标签组合分序列，机器维度由查询层处理。</li>
 * </ul>
 */
@org.springframework.modulith.NamedInterface("analysis")
@org.springframework.stereotype.Component
@org.springframework.context.annotation.DependsOn("metricConfig")
public class MetricAnalyzer implements Analyzer {

    private final HourlyReportStore store;

    private final MetricHourRank rank;

    private final MetricLabelMetadata metadata;

    public MetricAnalyzer(HourlyReportStore store, MetricHourRank rank) {
        this(store, rank, null);
    }
    @org.springframework.beans.factory.annotation.Autowired
    public MetricAnalyzer(HourlyReportStore store, MetricHourRank rank, MetricLabelMetadata metadata) {
        this.store = store;
        this.rank = rank;
        this.metadata = metadata;
    }
    @Override
    public String domain() {
        return "metric";
    }
    @Override
    public void analyze(MessageTree tree) {
        for (RawNode node : tree.getNodes()) {
            if (node.getKind() != NodeKind.METRIC) {
                continue;
            }
            MetricValue metric = node.getMetric();
            if (Objects.isNull(metric)) {
                continue;
            }
            Instant eventTime = Instant.ofEpochMilli(node.getTimestamp());
            String labels = MetricLabels.canonicalize(metric.getLabels());
            String metricName = Objects.isNull(metric.getName()) ? node.getName() : metric.getName();

            String owner = rank.record(tree.getServiceName(), metricName, labels, eventTime);
            store.addValue(SeriesKey.metric(tree.getServiceName(), metricName, owner), eventTime, metric.getValue());
            if (Objects.nonNull(metadata)) metadata.record(tree.getServiceName(), metricName, metric.getLabels(), owner, eventTime);
        }
    }
}
