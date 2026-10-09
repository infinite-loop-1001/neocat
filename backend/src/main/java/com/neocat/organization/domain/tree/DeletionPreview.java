package com.neocat.organization.domain.tree;

import java.util.List;

import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.ToString;
import org.springframework.modulith.NamedInterface;

/**
 * 删除叶子的影响预览（PRD 01 §5.3）。
 *
 * <p>管理员确认删除前必须看到完整影响范围：组织名、其下大盘（含各自卡片数）、
 * 组织告警规则数、有效成员数。
 *
 * <p>大盘明细以列表形式给出，而不是只给总数 ——
 * 因为管理员需要判断「删掉的是哪几块大盘」，
 * 只看到「3 块大盘」无法判断后果（PRD 01 §5.3「展示影响范围」）。
 */
@NamedInterface("isOrganization")
@Getter
@EqualsAndHashCode
@ToString
public class DeletionPreview {
    private final long orgId;

    private final String orgName;

    private final List<DashboardSummary> dashboards;

    private final long alertRuleCount;

    private final long memberCount;

    public DeletionPreview(long orgId, String orgName, List<DashboardSummary> dashboards, long alertRuleCount, long memberCount) {
        this.orgId = orgId;
        this.orgName = orgName;
        this.dashboards = dashboards;
        this.alertRuleCount = alertRuleCount;
        this.memberCount = memberCount;
    }

    /**
     * 大盘总数。
     */
    public long dashboardCount() {
        return dashboards.size();
    }

    /**
     * 卡片总数。
     */
    public long cardCount() {
        return dashboards.stream().mapToLong(DashboardSummary::getCardCount).sum();
    }
}