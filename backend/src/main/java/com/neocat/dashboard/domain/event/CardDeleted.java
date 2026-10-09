package com.neocat.dashboard.domain.event;

import java.util.List;

import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.ToString;
import org.springframework.modulith.NamedInterface;

/**
 * 卡片被删除：关联规则**失效但保留配置**。
 *
 * <p>removedTargetIdentity：被删除卡片的目标标识，用于判断是否仍有其他卡片引用同一目标。
 */
@NamedInterface("dashboard")
@Getter
@EqualsAndHashCode
@ToString
public final class CardDeleted implements CardEvent {
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
