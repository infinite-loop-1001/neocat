package com.neocat.organization.domain.lifecycle;

import com.neocat.organization.domain.tree.DeletionPreview;

import java.util.List;
import org.springframework.modulith.NamedInterface;

/**
 * 组织资源归属查询（大盘、组织告警）。
 *
 * <p>由 isOrganization 的同步资源投影实现；dashboard / alert 只通过组织内部
 * API 更新投影，资源删除由同步事件驱动（模块边界见 {@code ModuleBoundarySpec}）。
 *
 * <p>用途有两处：
 * <ul>
 *   <li>判定拓扑约束：有资源的叶子不能新增子节点（PRD 01 §5.2）；</li>
 *   <li>删除叶子前的影响预览与原子级联删除（PRD 01 §5.3）。</li>
 * </ul>
 */
@NamedInterface("isOrganization")
public interface OrgResourceGateway {

    /** 该叶子是否已有大盘。 */
    boolean hasDashboards(long orgId);

    /** 该叶子是否已有组织告警规则。 */
    boolean hasAlertRules(long orgId);

    /**
     * 该叶子下的全部大盘概况（含各自卡片数），用于删除影响预览。
     *
     * <p>按创建顺序返回，使预览展示稳定。
     */
    List<DeletionPreview.DashboardSummary> dashboardsOf(long orgId);

    /** 该叶子的组织告警规则数。 */
    long alertRuleCount(long orgId);

    /** 级联删除该组织的全部大盘、卡片与组织告警。 */
    void deleteAllOf(long orgId);

    /** 该叶子最近一次涉及的告警规则 ID，用于通知失效。 */
    void invalidateAlertRulesOf(long orgId);
}
