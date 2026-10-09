package com.neocat.query.domain.report;

import java.math.BigDecimal;

import com.google.common.collect.Lists;
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
import java.util.Objects;

import org.apache.commons.collections4.CollectionUtils;
import org.springframework.modulith.NamedInterface;
import com.neocat.query.domain.stat.Merged;

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
@NamedInterface("query")
public class DependencyQueryService {

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
        if (CollectionUtils.isEmpty(rows) || Objects.isNull(direction)) {
            return Lists.newArrayList();
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
            Merged merged = calculator.merge(group);
            BigDecimal avg = calculator.computeFrom(merged, Stat.AVG, coveredSeconds);
            BigDecimal rate = calculator.computeFrom(merged, Stat.FAILURE_RATE, coveredSeconds);
            BigDecimal tp99 = percentiles.percentile(group, Stat.TP99.percentileFraction());
            result.add(new DependencyRow(peer, merged.getCount(), merged.getFailCount(), rate, avg, tp99, null));
        });

        result.sort(Comparator.comparingLong(DependencyRow::getCalls).reversed()
                .thenComparing(DependencyRow::getPeerService));
        return result;
    }
}
