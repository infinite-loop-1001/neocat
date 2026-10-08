package com.neocat.analysis.domain.analyzer;

import com.neocat.analysis.domain.bucket.HourlyReportStore;
import com.neocat.analysis.domain.bucket.SeriesKey;
import com.neocat.analysis.domain.bucket.SeriesKind;

import com.neocat.ingest.domain.tree.HeartbeatValue;
import com.neocat.ingest.domain.tree.MessageTree;
import com.neocat.ingest.domain.tree.NodeKind;
import com.neocat.ingest.domain.tree.RawNode;

import java.time.Instant;
import java.math.BigDecimal;
import java.util.Objects;

import org.springframework.modulith.NamedInterface;
import org.springframework.stereotype.Component;

/**
 * Heartbeat 分析器（PRD 02 §9、PRD 03 §10）。
 *
 * <p>规则：
 * <ul>
 *   <li>支持固定的 20 项 JVM 指标，仅写存在且非负的采样；</li>
 *   <li>**只写实例行**（instance = 实际上报的 instanceId），不写全机器聚合行。
 *       这是「不将不同 JVM 的堆、线程等相加」在存储层上的强制保证：聚合行不存在，
 *       查询层就不可能给出被相加过的值；</li>
 *   <li>趋势按事件时间选最后采样，Gauge 和累计 GC 都不求和；</li>
 *   <li>缺少 Heartbeat 载荷的节点跳过，不影响其他节点。</li>
 * </ul>
 */
@NamedInterface("analysis")
@Component
public class HeartbeatAnalyzer implements Analyzer {

    private final HourlyReportStore store;

    public HeartbeatAnalyzer(HourlyReportStore store) {
        this.store = store;
    }

    @Override
    public String domain() {
        return "heartbeat";
    }

    @Override
    public void analyze(MessageTree tree) {
        for (RawNode node : tree.getNodes()) {
            if (!Objects.equals(node.getKind(), NodeKind.HEARTBEAT)) {
                continue;
            }
            HeartbeatValue hb = node.getHeartbeat();
            if (Objects.isNull(hb)) {
                continue;
            }
            Instant eventTime = Instant.ofEpochMilli(node.getTimestamp());
            for (JvmMetric metric : JvmMetric.values()) {
                Long value = hb.getValues().get(metric.seriesName());
                if (Objects.nonNull(value) && value >= 0) record(tree, eventTime, metric, value);
            }
        }
    }

    /**
     * 写入实例级序列。注意 category 固定为 {@code jvm}，instance 为真实实例 ID，
     * 绝不使用 {@link SeriesKey#ALL}。
     */
    private void record(MessageTree tree, Instant eventTime, JvmMetric metric, long value) {
        SeriesKey key = SeriesKey.of(tree.getServiceName(), SeriesKind.HEARTBEAT,
                "jvm", metric.seriesName(), tree.getInstanceId());
        store.addValue(key, eventTime, BigDecimal.valueOf(value));
    }
}
