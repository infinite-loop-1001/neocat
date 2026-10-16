package com.neocat.organization.api.internal;

import java.util.Set;

public interface OrganizationAccess {
    boolean isLeaf(long orgId);
    /** 账号（业务标识）是否为该叶子的有效成员（直系或祖先继承）。 */
    boolean isEffectiveMember(String userAccount, long orgId);
    Set<Long> effectiveLeaves(String userAccount);
}
