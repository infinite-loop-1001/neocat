package com.neocat.dashboard.domain.dashboard;

import com.google.common.collect.Lists;
import com.neocat.dashboard.domain.access.OrgAccessGateway;
import com.neocat.dashboard.domain.card.Card;
import com.neocat.dashboard.domain.event.CardEvent;
import com.neocat.dashboard.domain.event.CardEventPublisher;
import com.neocat.dashboard.domain.formula.FormulaParser;

import com.neocat.common.error.exception.AuthorizationException;
import com.neocat.common.error.exception.ConflictException;
import com.neocat.common.error.exception.ResourceNotFoundException;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import static com.neocat.common.error.ErrorCode.DASHBOARD_NOT_FOUND;
import static com.neocat.common.error.ErrorCode.NOT_LEAF;
import static com.neocat.common.error.ErrorCode.NOT_ORG_MEMBER;

/**
 * 大盘生命周期与权限用例（PRD 05 §1、§2、§10）。
 *
 * <p>权限判定统一走 {@link #requireMember}，因此：
 * <ul>
 *   <li>创建、查看、改名、删除四处都不会漏校验；</li>
 *   <li>**按 ID 访问也先校验权限**，实现「不能通过猜测 ID 绕过权限」；</li>
 *   <li>不接收调用者的系统角色参数，**结构上不可能为管理员开后门**（PRD 01 §7.2：管理员无旁路）。</li>
 * </ul>
 *
 * <p>权限变化即时生效：每次调用都重新查询 {@link OrgAccessGateway}，
 * 不缓存成员资格，因此成员被移除后紧接着的请求就会失败。
 */
@org.springframework.stereotype.Service
@org.springframework.modulith.NamedInterface("dashboard")
public class DashboardService {

    private final DashboardRepository dashboards;

    private final OrgAccessGateway orgAccess;

    private final CardEventPublisher cardEvents;

    public DashboardService(DashboardRepository dashboards, OrgAccessGateway orgAccess) {
        this(dashboards, orgAccess, null);
    }
    @org.springframework.beans.factory.annotation.Autowired
    public DashboardService(DashboardRepository dashboards, OrgAccessGateway orgAccess,
                            CardEventPublisher cardEvents) {
        this.dashboards = dashboards;
        this.orgAccess = orgAccess;
        this.cardEvents = cardEvents;
    }
    /** 创建大盘：组织必须是叶子，且调用者必须是其有效成员。 */
    @com.neocat.common.locking.MySqlLocked("metadata")
    public Dashboard create(long accountId, long orgId, String name) {
        if (!orgAccess.isLeaf(orgId)) {
            throw new ConflictException(NOT_LEAF);
        }
        requireMember(accountId, orgId);
        return dashboards.save(new Dashboard(0, orgId, name, 0));
    }
    /**
     * 查看某叶子的大盘列表。
     *
     * <p>非成员返回**空列表**而非报错：入口本就不应出现，列表为空即可，
     * 无需向前端泄露该组织是否存在大盘。
     */
    public List<Dashboard> list(long accountId, long orgId) {
        if (!orgAccess.isLeaf(orgId) || !orgAccess.isEffectiveMember(accountId, orgId)) {
            return Lists.newArrayList();
        }
        return dashboards.byOrg(orgId);
    }
    /**
     * 当前用户可访问的全部大盘（按有效叶子组织聚合）。
     *
     * <p>只返回其有效成员叶子组织上的大盘：非成员看不到入口（PRD 05 §1）。
     */
    public List<Dashboard> listAll(long accountId) {
        List<Dashboard> result = new ArrayList<>();
        for (Long orgId : orgAccess.effectiveLeaves(accountId)) {
            result.addAll(dashboards.byOrg(orgId));
        }
        return List.copyOf(result);
    }
    /**
     * 按 ID 读取大盘并要求访问权限。
     *
     * <p>顺序：先取大盘（不存在 → DASHBOARD_NOT_FOUND），再校验其组织的成员资格（非成员 → NOT_ORG_MEMBER）。
     * 这样既能区分「不存在」与「无权限」，又不会让非成员读到大盘内容。
     */
    public Dashboard requireAccessible(long accountId, long dashboardId) {
        Dashboard dashboard = java.util.Optional.ofNullable(dashboards.findById(dashboardId))
                .orElseThrow(() -> new ResourceNotFoundException(DASHBOARD_NOT_FOUND, dashboardId));
        requireMember(accountId, dashboard.getOrgId());
        return dashboard;
    }
    @com.neocat.common.locking.MySqlLocked("metadata")
    public Dashboard rename(long accountId, long dashboardId, String newName) {
        Dashboard dashboard = requireAccessible(accountId, dashboardId);
        return dashboards.save(new Dashboard(dashboard.getId(), dashboard.getOrgId(), newName, dashboard.getOrderNo()));
    }
    @org.springframework.transaction.annotation.Transactional
    @com.neocat.common.locking.MySqlLocked("metadata")
    public void delete(long accountId, long dashboardId) {
        Dashboard dashboard = requireAccessible(accountId, dashboardId);
        List<Card> removed = dashboards.cardsOf(dashboard.getId());
        dashboards.delete(dashboard.getId());
        if (Objects.nonNull(cardEvents)) {
            FormulaParser parser = new FormulaParser();
            for (Card card : removed) {
                var parsed = parser.parse(card.getFormula());
                List<String> stats = parsed.valid() ? parsed.getFormula().referencedStats().stream()
                        .map(Enum::name).distinct().toList() : Lists.newArrayList();
                cardEvents.publish(new CardEvent.CardDeleted(card.getId(), dashboard.getId(), dashboard.getOrgId(),
                        card.targetIdentity(), stats));
            }
        }
    }

    // ── 内部 ─────────────────────────────────────────────────

    /**
     * 要求调用者是该组织的有效成员。
     *
     * <p>注意：这里**不接收角色参数**。管理员的系统角色无法在本方法中体现，
     * 因此无法通过参数传递获得旁路。
     */
    private void requireMember(long accountId, long orgId) {
        if (!orgAccess.isEffectiveMember(accountId, orgId)) {
            throw new AuthorizationException(NOT_ORG_MEMBER);
        }
    }
}
