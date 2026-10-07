package com.neocat.dashboard.domain.card;

import java.util.List;

/**
 * 卡片维度视图（PRD 05 §6、§7）。
 *
 * <p>规则：
 * <ul>
 *   <li>卡片**默认展示全部机器聚合结果**；</li>
 *   <li>成员可以进入维度下钻：查看各机器结果、选择机器对比、Top N 与分页明细、返回聚合；</li>
 *   <li>**机器下钻只用于诊断，不改变卡片的默认目标**；</li>
 *   <li>阈值线只作用于聚合结果，下钻不产生逐机器阈值线。</li>
 * </ul>
 *
 * @param aggregated 聚合结果序列
 * @param byInstance 各机器结果；仅在下钻模式填充
 * @param drilled    当前是否为下钻模式
 * @param thresholdLines 阈值线（始终作用于聚合结果）
 */
@org.springframework.modulith.NamedInterface("dashboard")
@lombok.Getter
@lombok.EqualsAndHashCode
@lombok.ToString
public class CardDimensionView {
    private final List<CardPoint> aggregated;

    private final List<MachineSeries> byInstance;

    private final boolean drilled;

    private final List<ThresholdLine> thresholdLines;

    public CardDimensionView(List<CardPoint> aggregated, List<MachineSeries> byInstance, boolean drilled, List<ThresholdLine> thresholdLines) {
        this.aggregated = aggregated;
        this.byInstance = byInstance;
        this.drilled = drilled;
        this.thresholdLines = thresholdLines;
    }

    /**
     * 某台机器在卡片公式下的序列。
     */
    @org.springframework.modulith.NamedInterface("dashboard")
    @lombok.Getter
    @lombok.EqualsAndHashCode
    @lombok.ToString
    public static class MachineSeries {
        private final String instance;

        private final List<CardPoint> points;

        public MachineSeries(String instance, List<CardPoint> points) {
            this.instance = instance;
            this.points = points;
        }

    }
    /** 聚合结果中越过阈值线的点位数（仅用于展示，不驱动告警）。 */
    public long breaches() {
        if (thresholdLines == null || thresholdLines.isEmpty()) {
            return 0;
        }
        return aggregated.stream()
                .filter(p -> p.getValue() != null)
                .filter(p -> thresholdLines.stream().anyMatch(line -> line.getDirection() == ThresholdDirection.ABOVE
                        ? p.getValue() > line.getValue()
                        : p.getValue() < line.getValue()))
                .count();
    }
}


