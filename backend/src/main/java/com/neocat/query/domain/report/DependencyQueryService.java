package com.neocat.query.domain.report;

import com.neocat.query.domain.stat.PercentileMerger;
import com.neocat.query.domain.stat.Stat;
import com.neocat.query.domain.stat.StatCalculator;

import com.neocat.analysis.domain.bucket.AggregatedRow;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 依赖查询（PRD 04 §6、§8，链路 22）。
 *
 * <p>依赖是**长期服务关系汇总**：读取已有 DEPENDENCY 序列并聚合成列表，
 * **完全不查询下游服务是否存在或是否上报过**（PRD 04 §7）。
 * 因此「下游 MessageTree 缺失」在本服务中不可能导致丢边。
 *
 * <p>列表按调用次数降序：值班人员最关心调用量最大的依赖。
 * 分位由合并分布重算（统一走 {@link PercentileMerger}），不平均各边分位。
 */
@org.springframework.modulith.NamedInterface("query")
public class DependencyQueryService {

    /**
     * 依赖列表行。
     */
    @org.springframework.modulith.NamedInterface("query")
    @lombok.Getter
    @lombok.EqualsAndHashCode
    @lombok.ToString
    public static class DependencyRow {
        private final String peerService;

        private final long calls;

        private final long failures;

        private final Double failureRate;

        private final Double avgDuration;

        private final Double tp99;

        private final String sampleMessageId;

        public DependencyRow(String peerService, long calls, long failures, Double failureRate, Double avgDuration, Double tp99, String sampleMessageId) {
            this.peerService = peerService;
            this.calls = calls;
            this.failures = failures;
            this.failureRate = failureRate;
            this.avgDuration = avgDuration;
            this.tp99 = tp99;
            this.sampleMessageId = sampleMessageId;
        }

    }
    private final PercentileMerger percentiles;

    public DependencyQueryService() {
        this.percentiles = new PercentileMerger();
    }

    /**
     * 查询上下游列表。
     *
     * @param direction 方向；行内的 {@code type} 必须与方向匹配，否则被忽略
     * @param rows      DEPENDENCY 序列的行；{@code type} 为对端服务名
     */
    public List<DependencyRow> list(DependencyDirectionQuery direction, List<AggregatedRow> rows,
                                    long coveredSeconds) {
        if (rows == null || rows.isEmpty() || direction == null) {
            return List.of();
        }
        String expectedType = direction.name().toUpperCase(Locale.ROOT);

        Map<String, List<AggregatedRow>> grouped = new HashMap<>();
        for (AggregatedRow row : rows) {
            if (!expectedType.equalsIgnoreCase(row.key().getType())) {
                continue;
            }
            grouped.computeIfAbsent(row.key().getName(), k -> new ArrayList<>()).add(row);
        }

        StatCalculator calculator = new StatCalculator();
        List<DependencyRow> result = new ArrayList<>();
        grouped.forEach((peer, group) -> {
            StatCalculator.Merged merged = calculator.merge(group);
            Double avg = calculator.computeFrom(merged, Stat.AVG, coveredSeconds);
            Double rate = calculator.computeFrom(merged, Stat.FAILURE_RATE, coveredSeconds);
            Double tp99 = percentiles.percentile(group, 0.99d);
            result.add(new DependencyRow(peer, merged.getCount(), merged.getFailCount(), rate, avg, tp99, null));
        });

        result.sort(Comparator.comparingLong(DependencyRow::getCalls).reversed()
                .thenComparing(DependencyRow::getPeerService));
        return result;
    }
}



