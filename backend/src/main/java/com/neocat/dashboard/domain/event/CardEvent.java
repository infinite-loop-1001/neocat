package com.neocat.dashboard.domain.event;

import java.util.List;

/**
 * 卡片变更事件（PRD 05 §8，技术方案 02 §4.2）。
 *
 * <p>dashboard 通过事件通知 alert，避免模块间双向编译依赖。
 * 事件载荷必须包含足以让 alert 重新解析目标与判断失效的全部信息。
 */
@org.springframework.modulith.NamedInterface("dashboard")
public sealed interface CardEvent {

    long getCardId();

    long getDashboardId();

    long getOrgId();

    /** 卡片公式被修改：关联规则应跟随新公式，并保存为关闭、窗口清零。 */
    @org.springframework.modulith.NamedInterface("dashboard")
    @lombok.Getter
    @lombok.EqualsAndHashCode
    @lombok.ToString
    public static final class CardTargetChanged implements CardEvent {
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
    /**
     * 卡片被删除：关联规则**失效但保留配置**。
     *
     * @param removedTargetIdentity 被删除卡片的目标标识，用于判断是否仍有其他卡片引用同一目标
     */
    @org.springframework.modulith.NamedInterface("dashboard")
    @lombok.Getter
    @lombok.EqualsAndHashCode
    @lombok.ToString
    public static final class CardDeleted implements CardEvent {
        private final long cardId;

        private final long dashboardId;

        private final long orgId;

        private final String removedTargetIdentity;

        private final List<String> affectedStats;

        public CardDeleted(long cardId, long dashboardId, long orgId, String removedTargetIdentity, List<String> affectedStats) {
            this.cardId = cardId;
            this.dashboardId = dashboardId;
            this.orgId = orgId;
            this.removedTargetIdentity = removedTargetIdentity;
            this.affectedStats = affectedStats;
        }

    }
}











