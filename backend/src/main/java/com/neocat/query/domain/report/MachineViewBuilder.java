package com.neocat.query.domain.report;

import com.neocat.query.domain.stat.Stat;
import com.neocat.query.domain.stat.StatCalculator;

import com.neocat.analysis.domain.bucket.AggregatedRow;
import com.neocat.analysis.domain.bucket.AggregationLevel;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.Objects;

/**
 * 机器维度视图构建（PRD 03 §7.3、§10；技术方案 03 §4.3）。
 *
 * <p>构建规则：
 * <ol>
 *   <li>剔除全机器聚合行（{@code instance == "all"}），否则会与机器行重复计数；</li>
 *   <li>按当前统计项的贡献值降序排序；</li>
 *   <li>取前 {@code topN} 为明细行；其余在 {@code mergeOther = true} 时
 *       **把原始行合并成一个虚拟机器行**，统计项由合并分布重算；</li>
 *   <li>传入 {@code selected} 时只返回选中机器，且 <b>other 为 null</b>，
 *       满足「手动选机器后展示选中机器，不再自动加入 other」；</li>
 *   <li>Heartbeat（{@code mergeOther = false}）Top N 之外不合并，全量仍在 {@code all} 中可查。</li>
 * </ol>
 */
@org.springframework.modulith.NamedInterface("query")
public class MachineViewBuilder {

    public MachineView build(List<AggregatedRow> rows, Stat stat, int topN,
                             boolean mergeOther, List<String> selected, long coveredSeconds) {
        List<AggregatedRow> machines = filterMachineRows(rows);
        StatCalculator calculator = new StatCalculator();

        List<AggregatedRow> sorted = new ArrayList<>(machines);
        sorted.sort(Comparator
                .comparingDouble((AggregatedRow row) -> contribution(row, calculator, stat, coveredSeconds))
                .reversed()
                .thenComparing(row -> row.key().getInstance()));

        List<MachineRow> all = sorted.stream()
                .map(row -> toMachineRow(row, calculator, stat, coveredSeconds))
                .toList();

        // 手动勾选模式：只返回选中机器，不补 other
        if (Objects.nonNull(selected) && !selected.isEmpty()) {
            List<MachineRow> selectedRows = sorted.stream()
                    .filter(row -> selected.contains(row.key().getInstance()))
                    .map(row -> toMachineRow(row, calculator, stat, coveredSeconds))
                    .toList();
            return new MachineView(List.of(), null, all, selectedRows);
        }

        int limit = Math.max(0, topN);
        List<MachineRow> top = sorted.stream()
                .limit(limit)
                .map(row -> toMachineRow(row, calculator, stat, coveredSeconds))
                .toList();

        MachineRow other = null;
        if (mergeOther && sorted.size() > limit) {
            List<AggregatedRow> rest = sorted.subList(limit, sorted.size());
            other = toMergedRow(rest, calculator, stat, coveredSeconds);
        }

        return new MachineView(top, other, all, List.of());
    }

    // ── 内部 ─────────────────────────────────────────────────

    /** 排除全机器聚合行。 */
    private List<AggregatedRow> filterMachineRows(List<AggregatedRow> rows) {
        if (Objects.isNull(rows)) {
            return List.of();
        }
        List<AggregatedRow> machines = new ArrayList<>();
        for (AggregatedRow row : rows) {
            String instance = row.key().getInstance();
            if (Objects.isNull(instance) || SeriesKeyHelper.ALL.equals(instance)) {
                continue;
            }
            machines.add(row);
        }
        return machines;
    }
    private MachineRow toMachineRow(AggregatedRow row, StatCalculator calculator, Stat stat, long coveredSeconds) {
        StatCalculator.Merged merged = calculator.merge(List.of(row));
        return new MachineRow(
                row.key().getInstance(),
                merged.getCount(),
                merged.getFailCount(),
                calculator.computeFrom(merged, Stat.AVG, coveredSeconds),
                calculator.computeFrom(merged, Stat.TP99, coveredSeconds),
                calculator.computeFrom(merged, Stat.QPS, coveredSeconds),
                contributionOf(merged, stat, coveredSeconds));
    }
    /**
     * 合并 Top N 之外的机器为一行 other。
     *
     * <p>统计项由**合并后的分子与分布**重算，而不是对各机器已算出的值取平均，
     * 这样 other 行的 avg 与分位仍然正确。
     */
    private MachineRow toMergedRow(List<AggregatedRow> rest, StatCalculator calculator,
                                   Stat stat, long coveredSeconds) {
        StatCalculator.Merged merged = calculator.merge(rest);
        return new MachineRow(
                MachineRow.OTHER,
                merged.getCount(),
                merged.getFailCount(),
                calculator.computeFrom(merged, Stat.AVG, coveredSeconds),
                calculator.computeFrom(merged, Stat.TP99, coveredSeconds),
                calculator.computeFrom(merged, Stat.QPS, coveredSeconds),
                contributionOf(merged, stat, coveredSeconds));
    }
    private double contribution(AggregatedRow row, StatCalculator calculator, Stat stat, long coveredSeconds) {
        Double value = calculator.compute(List.of(row), stat, coveredSeconds);
        return Objects.isNull(value) ? Double.NEGATIVE_INFINITY : value;
    }
    private Double contributionOf(StatCalculator.Merged merged, Stat stat, long coveredSeconds) {
        return new StatCalculator().computeFrom(merged, stat, coveredSeconds);
    }
    /**
     * 内部小工具：避免直接依赖 analysis 模块的常量名。
     */
    private static class SeriesKeyHelper {
        static final String ALL = "all";
    }
}
