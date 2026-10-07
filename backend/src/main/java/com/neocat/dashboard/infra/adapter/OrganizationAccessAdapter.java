package com.neocat.dashboard.infra.adapter;

import com.neocat.dashboard.domain.access.OrgAccessGateway;
import com.neocat.organization.api.internal.OrganizationAccess;
import org.springframework.stereotype.Component;

import java.util.Set;

@Component
public class OrganizationAccessAdapter implements OrgAccessGateway {
    private final OrganizationAccess organizations;

    public OrganizationAccessAdapter(OrganizationAccess organizations) {
        this.organizations = organizations;
    }
    @Override
    public boolean isEffectiveMember(long accountId, long orgId) {
        return organizations.isEffectiveMember(accountId, orgId);
    }
    @Override
    public boolean isLeaf(long orgId) {
        return organizations.isLeaf(orgId);
    }
    @Override
    public Set<Long> effectiveLeaves(long accountId) {
        return organizations.effectiveLeaves(accountId);
    }
}
