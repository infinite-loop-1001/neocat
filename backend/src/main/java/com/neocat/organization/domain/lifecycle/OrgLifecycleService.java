package com.neocat.organization.domain.lifecycle;

import com.neocat.organization.domain.membership.EffectiveLeafRepository;
import com.neocat.organization.domain.membership.MembershipRepository;
import com.neocat.organization.domain.membership.OrgMembershipService;
import com.neocat.organization.domain.tree.DeletionPreview;
import com.neocat.organization.domain.tree.OrgNode;
import com.neocat.organization.domain.tree.OrgNodeRepository;
import com.neocat.common.error.exception.BusinessRuleException;
import com.neocat.common.error.exception.ConflictException;
import com.neocat.common.error.exception.ResourceNotFoundException;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.Objects;
import static com.neocat.common.error.ErrorCode.CONFIRM_NAME_MISMATCH;
import static com.neocat.common.error.ErrorCode.HAS_CHILDREN;
import static com.neocat.common.error.ErrorCode.LEAF_HAS_RESOURCES;
import static com.neocat.common.error.ErrorCode.ORG_NOT_FOUND;
import org.apache.commons.collections4.CollectionUtils;
import org.springframework.modulith.NamedInterface;
import org.springframework.stereotype.Service;
import com.neocat.common.locking.MySqlLocked;
import java.util.Optional;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;
/**
 * 组织生命周期用例（PRD 01 §5.2 / §5.3）。
 */
@Service
@NamedInterface("isOrganization")
public class OrgLifecycleService {

    private final OrgNodeRepository nodes;

    private final OrgResourceGateway resources;

    private final MembershipRepository memberships;

    private final EffectiveLeafRepository effectiveLeaves;

    private final OrgMembershipService membershipService;

    public OrgLifecycleService(OrgNodeRepository nodes, OrgResourceGateway resources,
                                MembershipRepository memberships, EffectiveLeafRepository effectiveLeaves) {
        this(nodes, resources, memberships, effectiveLeaves, null);
    }
    @Autowired
    public OrgLifecycleService(OrgNodeRepository nodes, OrgResourceGateway resources,
                               MembershipRepository memberships, EffectiveLeafRepository effectiveLeaves,
                               OrgMembershipService membershipService) {
        this.nodes = nodes;
        this.resources = resources;
        this.memberships = memberships;
        this.effectiveLeaves = effectiveLeaves;
        this.membershipService = membershipService;
    }
    /**
     * §5.2 叶子新增子节点前的资源检查。
     *
     * <p>只有当父节点当前是叶子、且已拥有大盘或组织告警时才阻止；
     * 非叶子新增子节点不受该约束。
     */
    @Transactional
    @MySqlLocked("metadata")
    public OrgNode addChildNode(long parentId, String name) {
        requireNode(parentId);
        if (isLeaf(parentId) && (resources.hasDashboards(parentId) || resources.hasAlertRules(parentId))) {
            throw new BusinessRuleException(LEAF_HAS_RESOURCES);
        }
        OrgNode created = nodes.create(name, parentId);
        if (Objects.nonNull(membershipService)) membershipService.recomputeAll();
        return created;
    }
    /** §5.3 删除前的影响预览：组织名、大盘数、卡片数、组织告警数、有效成员数。 */
    public DeletionPreview previewDeletion(long orgId) {
        OrgNode node = requireNode(orgId);
        var dashboards = resources.dashboardsOf(orgId);
        long rules = resources.alertRuleCount(orgId);
        long members = effectiveLeaves.membersOf(orgId).size();
        return new DeletionPreview(node.getId(), node.getName(), dashboards, rules, members);
    }
    /**
     * §5.3 二次确认后原子级联删除。
     *
     * <p>顺序：校验存在 → 校验无子节点 → 校验确认名称 → 级联删除资源 →
     * 删除成员关系与有效叶子快照 → 删除节点本身。
     */
    @Transactional
    @MySqlLocked("metadata")
    public void deleteNode(long orgId, String confirmName) {
        OrgNode node = requireNode(orgId);
        if (!isLeaf(orgId)) {
            throw new ConflictException(HAS_CHILDREN);
        }
        if (!Objects.equals(node.getName(), confirmName)) {
            throw new ConflictException(CONFIRM_NAME_MISMATCH);
        }

        // 收集受影响账号后再清理，保证「所有成员立即失去该叶子权限」
        Set<Long> affected = new HashSet<>();
        affected.addAll(effectiveLeaves.membersOf(orgId));
        affected.addAll(memberships.membersOf(orgId));

        resources.deleteAllOf(orgId);
        for (Long accountId : affected) {
            memberships.remove(orgId, accountId);
        }
        effectiveLeaves.removeOrg(orgId);
        // 先摘除节点，再重算受影响用户：处理「通过其他路径仍可达其他叶子」的情形
        nodes.delete(orgId);
        for (Long accountId : affected) {
            Set<Long> recomputed = new HashSet<>();
            for (Long otherOrg : memberships.orgsOf(accountId)) {
                recomputed.addAll(leavesOf(otherOrg));
            }
            effectiveLeaves.replaceAll(accountId, recomputed);
        }
    }
    public boolean isLeaf(long orgId) {
        return CollectionUtils.isEmpty(nodes.childrenOf(orgId));
    }

    // ── 内部 ─────────────────────────────────────────────────

    private OrgNode requireNode(long orgId) {
        return Optional.ofNullable(nodes.findById(orgId))
                .orElseThrow(() -> new ResourceNotFoundException(ORG_NOT_FOUND, orgId));
    }
    private Set<Long> leavesOf(long orgId) {
        Set<Long> leaves = new HashSet<>();
        List<OrgNode> all = nodes.findAll();
        if (all.stream().noneMatch(n -> Objects.nonNull(n.getParentId()) && n.getParentId() == orgId)) {
            // 自身已是叶子
            if (Objects.nonNull(nodes.findById(orgId))) {
                leaves.add(orgId);
            }
            return leaves;
        }
        for (OrgNode child : all) {
            if (Objects.nonNull(child.getParentId()) && child.getParentId() == orgId) {
                leaves.addAll(leavesOf(child.getId()));
            }
        }
        return leaves;
    }
}
