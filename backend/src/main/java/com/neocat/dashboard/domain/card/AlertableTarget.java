package com.neocat.dashboard.domain.card;

import com.neocat.query.domain.stat.Stat;

import java.util.List;
import java.util.Objects;

/**
 * 可作为组织告警目标的对象（PRD 05 §9、PRD 06 §8）。
 *
 * <p>两类：
 * <ul>
 *   <li>{@link AlertableTargetKind#RAW_STAT}：卡片引用的**原始统计项**；</li>
 *   <li>{@link AlertableTargetKind#CARD_RESULT}：卡片的**计算结果**。</li>
 * </ul>
 *
 * @param kind       目标类型
 * @param cardId     卡片结果对应的卡片；原始统计项为 0
 * @param service    服务
 * @param targetKind 报表类型
 * @param targetType 分类
 * @param targetName Name
 * @param metricLabels Metric 标签串
 * @param stats      涉及的统计项；卡片结果为其公式引用的统计项
 */
@org.springframework.modulith.NamedInterface("dashboard")
@lombok.Getter
@lombok.EqualsAndHashCode
@lombok.ToString
public class AlertableTarget {
    private final AlertableTargetKind kind;

    private final long cardId;

    private final String service;

    private final String targetKind;

    private final String targetType;

    private final String targetName;

    private final String metricLabels;

    private final List<Stat> stats;

    public AlertableTarget(AlertableTargetKind kind, long cardId, String service, String targetKind, String targetType, String targetName, String metricLabels, List<Stat> stats) {
        this.kind = kind;
        this.cardId = cardId;
        this.service = service;
        this.targetKind = targetKind;
        this.targetType = targetType;
        this.targetName = targetName;
        this.metricLabels = metricLabels;
        this.stats = stats;
    }

    /** 目标身份：用于判断是否仍被引用。 */
    public String identity() {
        return kind + "|" + service + "|" + targetKind + "|"
                + (Objects.isNull(targetType) ? "" : targetType) + "|"
                + (Objects.isNull(targetName) ? "" : targetName) + "|"
                + (Objects.isNull(metricLabels) ? "" : metricLabels);
    }
}
