package com.neocat.organization.domain.membership;

import com.google.common.collect.Sets;
import com.neocat.organization.domain.tree.OrgNode;
import com.neocat.organization.domain.tree.OrgNodeRepository;
import com.neocat.common.error.exception.ResourceNotFoundException;
import com.neocat.identity.api.internal.AccountDirectory;
import com.neocat.organization.api.internal.EffectiveMembershipChanged;
import org.springframework.context.ApplicationEventPublisher;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.Objects;
import static com.neocat.common.error.ErrorCode.ORG_NOT_FOUND;
import static com.neocat.common.error.ErrorCode.USER_NOT_FOUND;
import org.apache.commons.collections4.CollectionUtils;
import org.springframework.modulith.NamedInterface;
import org.springframework.stereotype.Service;
import com.neocat.common.locking.MySqlLocked;
import org.springframework.transaction.annotation.Transactional;

/**
 * 组织成员与有效叶子权限用例（PRD 01 §6）。
 *
 * <p>有效叶子集合 = ⋃<sub>n ∈ 用户直接加入的节点</sub> leaves(n)，
 * 其中 leaves(叶子) = {叶子}，leaves(非叶子) = 其所有后代叶子。
 * 每次成员关系变更后立即重算，保证「权限变化即时生效」。
 */
@Service
@NamedInterface("organization")
public class OrgMembershipService {

    private final OrgNodeRepository nodes;

    private final MembershipRepository memberships;

    private final EffectiveLeafRepository effectiveLeaves;

    private final ApplicationEventPublisher events;

    private final AccountDirectory accounts;

    public OrgMembershipService(OrgNodeRepository nodes, MembershipRepository memberships,
                                EffectiveLeafRepository effectiveLeaves, ApplicationEventPublisher events,
                                AccountDirectory accounts) {
        this.nodes = nodes;
        this.memberships = memberships;
        this.effectiveLeaves = effectiveLeaves;
        this.events = events;
        this.accounts = accounts;
    }
    /** §6 加入成员：任意节点可加人，随后立即重算该用户的有效叶子。 */
    @Transactional
    @MySqlLocked("metadata")
    public void addMember(long orgId, String userAccount) {
        requireNode(orgId);
        if (!accounts.exists(userAccount)) {
            throw new ResourceNotFoundException(USER_NOT_FOUND, userAccount);
        }
        memberships.add(orgId, userAccount);
        recompute(userAccount);
    }
    /** §6 移除成员：立即重算；无任何路径时权限立即撤销。 */
    @Transactional
    @MySqlLocked("metadata")
    public void removeMember(long orgId, String userAccount) {
        memberships.remove(orgId, userAccount);
        recompute(userAccount);
    }
    /** §6 按当前树形与直接成员关系重算某用户的有效叶子集合。 */
    @Transactional
    @MySqlLocked("metadata")
    public void recompute(String userAccount) {
        Set<Long> previous = Objects.isNull(events) ? Sets.newHashSet() : effectiveLeaves.leavesOf(userAccount);
        Set<Long> leaves = new HashSet<>();
        for (Long orgId : memberships.orgsOf(userAccount)) {
            leaves.addAll(descendantLeaves(orgId));
        }
        effectiveLeaves.replaceAll(userAccount, leaves);
        if (Objects.nonNull(events)) {
            for (Long orgId : previous) {
                if (!leaves.contains(orgId)) events.publishEvent(new EffectiveMembershipChanged(userAccount, orgId, false));
            }
            for (Long orgId : leaves) {
                if (!previous.contains(orgId)) events.publishEvent(new EffectiveMembershipChanged(userAccount, orgId, true));
            }
        }
    }
    /** §6 重算所有用户（组织结构变化后调用）。 */
    @Transactional
    @MySqlLocked("metadata")
    public void recomputeAll() {
        Set<String> accounts = new HashSet<>();
        for (OrgNode node : nodes.findAll()) {
            accounts.addAll(memberships.membersOf(node.getId()));
        }
        accounts.forEach(this::recompute);
    }
    public Set<Long> effectiveLeaves(String userAccount) {
        return effectiveLeaves.leavesOf(userAccount);
    }
    /**
     * 该节点的直接成员账号集合。
     *
     * <p>组织树页展示的「成员数」是直接成员数；叶子权限则用 {@link #effectiveLeaves} 计算。
     */
    public Set<String> effectiveMembers(long orgId) {
        return memberships.membersOf(orgId);
    }
    /** 是否为该叶子的有效成员（直系或祖先继承）。 */
    public boolean isEffectiveMember(String userAccount, long leafOrgId) {
        return effectiveLeaves.leavesOf(userAccount).contains(leafOrgId);
    }

    // ── 内部 ─────────────────────────────────────────────────

    /**
     * 返回某节点的全部后代叶子；节点本身是叶子时返回其自身。
     * 使用迭代式广度优先，避免深度过大时栈溢出，并天然容忍环（已访问集合去重）。
     */
    private Set<Long> descendantLeaves(long orgId) {
        List<OrgNode> all = nodes.findAll();
        Set<Long> leaves = new HashSet<>();
        Set<Long> visited = new HashSet<>();
        Deque<Long> queue = new ArrayDeque<>();
        queue.add(orgId);
        visited.add(orgId);

        while (CollectionUtils.isNotEmpty(queue)) {
            long current = queue.poll();
            List<OrgNode> children = all.stream()
                    .filter(n -> Objects.nonNull(n.getParentId()) && n.getParentId() == current)
                    .toList();
            if (CollectionUtils.isEmpty(children)) {
                leaves.add(current);
            } else {
                for (OrgNode child : children) {
                    if (visited.add(child.getId())) {
                        queue.add(child.getId());
                    }
                }
            }
        }
        return leaves;
    }
    private void requireNode(long orgId) {
        if (Objects.isNull(nodes.findById(orgId))) {
            throw new ResourceNotFoundException(ORG_NOT_FOUND, orgId);
        }
    }
}
