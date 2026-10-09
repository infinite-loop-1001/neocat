package com.neocat.dashboard.domain.card.result;

import java.util.List;
import java.util.Objects;

import org.apache.commons.collections4.CollectionUtils;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.ToString;
import org.springframework.modulith.NamedInterface;
import com.neocat.dashboard.domain.card.CardPoint;
import com.neocat.dashboard.domain.card.ThresholdDirection;
import com.neocat.dashboard.domain.card.ThresholdLine;

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
 * <p>aggregated：聚合结果序列。
 * <p>byInstance：各机器结果；仅在下钻模式填充。
 * <p>drilled：当前是否为下钻模式。
 * <p>thresholdLines：阈值线（始终作用于聚合结果）。
 */
@NamedInterface("dashboard")
@Getter
@EqualsAndHashCode
@ToString
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
     * 聚合结果中越过阈值线的点位数（仅用于展示，不驱动告警）。
     */
    public long breaches() {
        if (CollectionUtils.isEmpty(thresholdLines)) {
            return 0;
        }
        return aggregated.stream()
                .filter(p -> Objects.nonNull(p.getValue()))
                .filter(p -> thresholdLines.stream().anyMatch(line -> Objects.equals(line.getDirection(), ThresholdDirection.ABOVE)
                        ? p.getValue().compareTo(line.getValue()) > 0
                        : p.getValue().compareTo(line.getValue()) < 0))
                .count();
    }
}
