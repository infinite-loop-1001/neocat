package com.neocat.query.domain.report;

import com.neocat.query.domain.stat.Stat;
import com.neocat.query.domain.stat.StatCalculator;
import com.neocat.analysis.domain.bucket.AggregatedRow;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.apache.commons.collections4.CollectionUtils;

/**
 * Type / Name 表查询（PRD 03 §7.1、§7.2、§8、§9）。
 *
 * <p>统计字段的**可见性**由报表类型决定，且与 {@link Stat#applicableToEvent()} /
 * {@link Stat#applicableToExceptionProblem()} 保持一致：
 *
 * <table border="1">
 *   <caption>字段可见性</caption>
 *   <tr><th>报表类型</th><th>次数/QPS</th><th>耗时</th><th>分位</th></tr>
 *   <tr><td>Transaction</td><td>✓</td><td>✓</td><td>✓</td></tr>
 *   <tr><td>Event</td><td>✓</td><td>—</td><td>—</td></tr>
 *   <tr><td>Problem / EXCEPTION</td><td>✓</td><td>—</td><td>—</td></tr>
 *   <tr><td>Problem / SLOW_*</td><td>✓</td><td>✓</td><td>✓</td></tr>
 * </table>
 *
 * <p>Type 层只做分类汇总，返回行的 {@code name} 为 {@code null}；
 * Name 层把范围限定在指定 Type 内。
 */
@org.springframework.modulith.NamedInterface("query")
@org.springframework.stereotype.Service
public class ReportTableService {

    private static final String KIND_EVENT = "EVENT";

    private static final String KIND_PROBLEM = "PROBLEM";

    private static final String CATEGORY_EXCEPTION = "EXCEPTION";

    /** Type 汇总表：按 Type 分组，按总量降序。 */
    public List<ReportRow> typeTable(String kind, List<AggregatedRow> rows, long coveredSeconds) {
        return table(kind, rows, null, coveredSeconds);
    }
    /** Name 列表：限定在指定 Type 内，按总量降序。 */
    public List<ReportRow> nameTable(String kind, String type, List<AggregatedRow> rows, long coveredSeconds) {
        return table(kind, rows, type, coveredSeconds);
    }

    // ── 内部 ─────────────────────────────────────────────────

    private List<ReportRow> table(String kind, List<AggregatedRow> rows, String typeFilter,
                                  long coveredSeconds) {
        if (CollectionUtils.isEmpty(rows)) {
            return List.of();
        }
        boolean byName = Objects.nonNull(typeFilter);
        StatCalculator calculator = new StatCalculator();

        Map<String, List<AggregatedRow>> grouped = new LinkedHashMap<>();
        for (AggregatedRow row : rows) {
            if (byName && !Objects.equals(typeFilter, row.key().getType())) {
                continue;
            }
            grouped.computeIfAbsent(row.key().getType(), k -> new ArrayList<>()).add(row);
        }

        List<ReportRow> result = new ArrayList<>();
        grouped.forEach((type, group) -> {
            if (byName) {
                // Name 层：每个 name 一行
                Map<String, List<AggregatedRow>> byNameGroup = new LinkedHashMap<>();
                for (AggregatedRow row : group) {
                    byNameGroup.computeIfAbsent(row.key().getName(), k -> new ArrayList<>()).add(row);
                }
                byNameGroup.forEach((name, nameGroup) ->
                        result.add(toRow(kind, type, name, nameGroup, calculator, coveredSeconds)));
            } else {
                result.add(toRow(kind, type, null, group, calculator, coveredSeconds));
            }
        });

        result.sort(Comparator.comparingLong(ReportRow::getTotal).reversed());
        return result;
    }
    private ReportRow toRow(String kind, String type, String name, List<AggregatedRow> group,
                            StatCalculator calculator, long coveredSeconds) {
        StatCalculator.Merged merged = calculator.merge(group);
        boolean durationVisible = durationVisible(kind, type);
        boolean percentileVisible = percentileVisible(kind, type);

        Double avg = durationVisible ? calculator.computeFrom(merged, Stat.AVG, coveredSeconds) : null;
        Double tp50 = percentileVisible ? calculator.computeFrom(merged, Stat.TP50, coveredSeconds) : null;
        Double tp90 = percentileVisible ? calculator.computeFrom(merged, Stat.TP90, coveredSeconds) : null;
        Double tp95 = percentileVisible ? calculator.computeFrom(merged, Stat.TP95, coveredSeconds) : null;
        Double tp99 = percentileVisible ? calculator.computeFrom(merged, Stat.TP99, coveredSeconds) : null;
        Double tp999 = percentileVisible ? calculator.computeFrom(merged, Stat.TP999, coveredSeconds) : null;
        Double tp9999 = percentileVisible ? calculator.computeFrom(merged, Stat.TP9999, coveredSeconds) : null;
        Double failureRate = calculator.computeFrom(merged, Stat.FAILURE_RATE, coveredSeconds);
        Double qps = calculator.computeFrom(merged, Stat.QPS, coveredSeconds);

        return new ReportRow(
                type,
                name,
                merged.getCount(),
                merged.getFailCount(),
                failureRate,
                durationVisible ? merged.getDurationMin() : 0L,
                durationVisible ? merged.getDurationMax() : 0L,
                avg,
                tp50, tp90, tp95, tp99, tp999, tp9999,
                qps);
    }
    /**
     * 耗时字段是否可见。
     *
     * <p>Event 没有耗时语义（PRD 03 §8），异常类 Problem 也不提供
     * （PRD 03 §9），两者都返回 false。
     */
    private boolean durationVisible(String kind, String type) {
        if (KIND_EVENT.equalsIgnoreCase(kind)) {
            return false;
        }
        return !CATEGORY_EXCEPTION.equalsIgnoreCase(type);
    }
    /** 分位字段是否可见：与耗时字段同规则。 */
    private boolean percentileVisible(String kind, String type) {
        return durationVisible(kind, type);
    }
}
