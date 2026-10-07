package com.neocat.organization.infra.adapter;

import com.neocat.organization.api.internal.OrganizationAccess;
import com.neocat.organization.domain.membership.EffectiveLeafRepository;
import com.neocat.organization.domain.tree.OrgNodeRepository;
import org.springframework.stereotype.Component;

import java.util.Set;

@Component
public class OrganizationAccessService implements OrganizationAccess {
    private final OrgNodeRepository nodes;

    private final EffectiveLeafRepository leaves;

    public OrganizationAccessService(OrgNodeRepository nodes, EffectiveLeafRepository leaves) {
        this.nodes = nodes;
        this.leaves = leaves;
    }
    @Override
    public boolean isLeaf(long orgId) {
        return java.util.Objects.nonNull(nodes.findById(orgId)) && nodes.childrenOf(orgId).isEmpty();
    }
    @Override
    public boolean isEffectiveMember(long accountId, long orgId) {
        return leaves.leavesOf(accountId).contains(orgId);
    }
    @Override
    public Set<Long> effectiveLeaves(long accountId) {
        return leaves.leavesOf(accountId);
    }
}
