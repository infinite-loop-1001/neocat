package com.neocat.analysis.domain.analyzer;

import com.neocat.analysis.domain.bucket.HourlyReportStore;
import com.neocat.analysis.domain.bucket.SeriesKey;
import com.neocat.analysis.domain.bucket.SeriesKind;

import com.neocat.ingest.domain.tree.ExceptionValue;
import com.neocat.ingest.domain.tree.MessageTree;
import com.neocat.ingest.domain.tree.NodeKind;
import com.neocat.ingest.domain.tree.RawNode;

import java.time.Instant;
import java.util.Objects;

/**
 * Problem 分析器（PRD 02 §9、PRD 03 §9）。
 *
 * <p>派生规则：
 * <ol>
 *   <li>Transaction / Event 节点 {@code status != "0"} → EXCEPTION。
 *       聚合键 = {@code exceptionName}；缺失时退化为节点 Name（保证不丢记录）。
 *       只计数，**不承载耗时分布**——异常不支持分位（PRD 03 §9）。</li>
 *   <li>Transaction 节点 category ∈ {URL, SQL, CALL, CACHE} 且 {@code durationMs >} 对应阈值
 *       → SLOW_URL / SLOW_SQL / SLOW_CALL / SLOW_CACHE。
 *       聚合键 = Transaction Name；承接耗时统计与分位。</li>
 *   <li>两条规则独立判定，因此同一次调用可以同时进入 EXCEPTION 与对应慢类。</li>
 * </ol>
 *
 * <p>阈值在构造时注入（来自 platform 配置），变更只影响此后创建的实例，
 * 从而天然满足「阈值只影响后续处理，不重算历史」。
 */
@org.springframework.modulith.NamedInterface("analysis")
@org.springframework.stereotype.Component
public class ProblemAnalyzer implements Analyzer {

    private final HourlyReportStore store;

    private final int slowUrlMs;

    private final int slowSqlMs;

    private final int slowCallMs;

    private final int slowCacheMs;

    @org.springframework.beans.factory.annotation.Autowired
    public ProblemAnalyzer(HourlyReportStore store, SlowThresholdProvider thresholds) {
        this(store, thresholds.urlMs(), thresholds.sqlMs(), thresholds.callMs(), thresholds.cacheMs());
    }

    public ProblemAnalyzer(HourlyReportStore store, int slowUrlMs, int slowSqlMs, int slowCallMs, int slowCacheMs) {
        this.store = store;
        this.slowUrlMs = slowUrlMs;
        this.slowSqlMs = slowSqlMs;
        this.slowCallMs = slowCallMs;
        this.slowCacheMs = slowCacheMs;
    }
    @Override
    public String domain() {
        return "problem";
    }
    @Override
    public void analyze(MessageTree tree) {
        for (RawNode node : tree.getNodes()) {
            if (node.getKind() != NodeKind.TRANSACTION && node.getKind() != NodeKind.EVENT) {
                continue;
            }
            Instant eventTime = Instant.ofEpochMilli(node.getTimestamp());
            if (!node.succeeded()) {
                recordException(tree, node, eventTime);
            }
            if (node.getKind() == NodeKind.TRANSACTION) {
                recordSlow(tree, node, eventTime);
            }
        }
    }
    /** 异常类：聚合键优先取异常名，缺失时退化为节点名。 */
    private void recordException(MessageTree tree, RawNode node, Instant eventTime) {
        String key = exceptionNameOf(node);
        if (Objects.isNull(key) || key.isBlank()) {
            key = Objects.isNull(node.getName()) ? "" : node.getName();
        }
        SeriesKey seriesKey = new SeriesKey(tree.getServiceName(), SeriesKind.PROBLEM,
                ProblemCategory.EXCEPTION.name(), key, SeriesKey.ALL,
                ProblemCategory.EXCEPTION.name(), "");
        // 只计数：异常不支持分位，因此不写入耗时分布。
        store.addCountOnly(seriesKey, eventTime, true);
    }
    /** 慢类：按 Transaction Name 聚合，承接耗时与分位。 */
    private void recordSlow(MessageTree tree, RawNode node, Instant eventTime) {
        ProblemCategory category = ProblemCategory.slowCategoryOf(node.getCategory());
        if (Objects.isNull(category)) {
            return;
        }
        if (node.getDurationMs() <= thresholdOf(category)) {
            return;
        }
        SeriesKey seriesKey = new SeriesKey(tree.getServiceName(), SeriesKind.PROBLEM,
                category.name(), node.getName(), SeriesKey.ALL, category.name(), "");
        store.add(seriesKey, eventTime, node.getDurationMs(), !node.succeeded());
    }
    private int thresholdOf(ProblemCategory category) {
        return switch (category) {
            case SLOW_URL -> slowUrlMs;
            case SLOW_SQL -> slowSqlMs;
            case SLOW_CALL -> slowCallMs;
            case SLOW_CACHE -> slowCacheMs;
            case EXCEPTION -> Integer.MAX_VALUE;
        };
    }
    private String exceptionNameOf(RawNode node) {
        ExceptionValue exception = node.getException();
        return Objects.isNull(exception) ? null : exception.getExceptionName();
    }
}

