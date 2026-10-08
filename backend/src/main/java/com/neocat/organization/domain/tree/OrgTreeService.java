package com.neocat.organization.domain.tree;

import com.neocat.organization.domain.lifecycle.OrgResourceGateway;
import com.neocat.organization.domain.membership.OrgMembershipService;
import com.neocat.common.error.exception.ConflictException;
import com.neocat.common.error.exception.ResourceNotFoundException;
import java.util.Objects;

import static com.neocat.common.error.ErrorCode.NAME_DUPLICATED;
import static com.neocat.common.error.ErrorCode.ORG_NOT_FOUND;
import static com.neocat.common.error.ErrorCode.PARENT_ORG_NOT_FOUND;
import static com.neocat.common.error.ErrorCode.LEAF_HAS_RESOURCES;
import org.apache.commons.collections4.CollectionUtils;
import org.springframework.modulith.NamedInterface;
import org.springframework.stereotype.Service;
import com.neocat.common.error.exception.BusinessRuleException;
import com.neocat.common.locking.MySqlLocked;
import java.util.Optional;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;

/**
 * 组织树用例（PRD 01 §5）。
 */
@Service
@NamedInterface("isOrganization")
public class OrgTreeService {

    private final OrgNodeRepository nodes;

    private final OrgResourceGateway resources;

    private final OrgMembershipService membershipService;

    public OrgTreeService(OrgNodeRepository nodes) {
        this(nodes, null);
    }
    public OrgTreeService(OrgNodeRepository nodes, OrgResourceGateway resources) {
        this(nodes, resources, null);
    }
    @Autowired
    public OrgTreeService(OrgNodeRepository nodes, OrgResourceGateway resources,
                          OrgMembershipService membershipService) {
        this.nodes = nodes;
        this.resources = resources;
        this.membershipService = membershipService;
    }
    /**
     * §5.1 创建节点：校验父节点存在、同父下名称唯一。
     * 新建节点必然是叶子，且默认没有大盘与组织告警。
     */
    @Transactional
    @MySqlLocked("metadata")
    public OrgNode createNode(String name, Long parentId) {
        if (Objects.nonNull(parentId) && Objects.isNull(nodes.findById(parentId))) {
            throw new ResourceNotFoundException(PARENT_ORG_NOT_FOUND, parentId);
        }
        requireUniqueName(name, parentId, null);
        if (Objects.nonNull(parentId) && CollectionUtils.isEmpty(nodes.childrenOf(parentId))
                && (resources.hasDashboards(parentId) || resources.hasAlertRules(parentId))) {
            throw new BusinessRuleException(LEAF_HAS_RESOURCES);
        }
        OrgNode created = nodes.create(name, parentId);
        if (Objects.nonNull(parentId) && Objects.nonNull(membershipService)) membershipService.recomputeAll();
        return created;
    }
    /** §5.1 改名：同父下名称唯一，且节点必须存在。 */
    @MySqlLocked("metadata")
    public OrgNode rename(long orgId, String newName) {
        OrgNode node = Optional.ofNullable(nodes.findById(orgId))
                .orElseThrow(() -> new ResourceNotFoundException(ORG_NOT_FOUND, orgId));
        requireUniqueName(newName, node.getParentId(), orgId);
        return nodes.save(new OrgNode(node.getId(), newName, node.getParentId()));
    }
    /** 叶子判定：不存在子节点（PRD 01 §5.1）。 */
    public boolean isLeaf(long orgId) {
        Optional.ofNullable(nodes.findById(orgId))
                .orElseThrow(() -> new ResourceNotFoundException(ORG_NOT_FOUND, orgId));
        return CollectionUtils.isEmpty(nodes.childrenOf(orgId));
    }

    // ── 内部 ─────────────────────────────────────────────────

    /**
     * 同父下名称唯一校验。{@code selfId} 非空时排除自身（改名场景）。
     */
    private void requireUniqueName(String name, Long parentId, Long selfId) {
        boolean duplicated = nodes.findAll().stream()
                .filter(n -> !Objects.equals(n.getId(), selfId))
                .filter(n -> Objects.equals(n.getParentId(), parentId))
                .anyMatch(n -> Objects.equals(n.getName(), name));
        if (duplicated) {
            throw new ConflictException(NAME_DUPLICATED, name);
        }
    }
}
