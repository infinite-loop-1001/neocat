package com.neocat.organization.api.internal;

import java.util.Set;

public interface OrganizationAccess {
    boolean isLeaf(long orgId);
    boolean isEffectiveMember(long accountId, long orgId);
    Set<Long> effectiveLeaves(long accountId);
}
