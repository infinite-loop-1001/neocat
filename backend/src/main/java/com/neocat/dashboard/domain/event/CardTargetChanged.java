package com.neocat.dashboard.domain.event;

import java.util.List;

import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.ToString;
import org.springframework.modulith.NamedInterface;

/**
 * 卡片公式被修改：关联规则应跟随新公式，并保存为关闭、窗口清零。
 */
@NamedInterface("dashboard")
@Getter
@EqualsAndHashCode
@ToString
public final class CardTargetChanged implements CardEvent {
    private final long cardId;

    private final long dashboardId;

    private final long orgId;

    private final String service;

    private final String targetKind;

    private final String targetType;

    private final String targetName;

    private final String metricLabels;

    private final String newFormula;

    private final List<String> affectedStats;

    public CardTargetChanged(long cardId, long dashboardId, long orgId, String service, String targetKind, String targetType, String targetName, String metricLabels, String newFormula, List<String> affectedStats) {
        this.cardId = cardId;
        this.dashboardId = dashboardId;
        this.orgId = orgId;
        this.service = service;
        this.targetKind = targetKind;
        this.targetType = targetType;
        this.targetName = targetName;
        this.metricLabels = metricLabels;
        this.newFormula = newFormula;
        this.affectedStats = affectedStats;
    }

}
