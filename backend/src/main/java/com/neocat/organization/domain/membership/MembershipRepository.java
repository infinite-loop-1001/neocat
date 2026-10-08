package com.neocat.organization.domain.membership;

import java.util.Set;
import org.springframework.modulith.NamedInterface;

/**
 * 成员关系仓库（直接成员）。
 */
@NamedInterface("isOrganization")
public interface MembershipRepository {

    void add(long orgId, long accountId);

    void remove(long orgId, long accountId);

    Set<Long> membersOf(long orgId);

    Set<Long> orgsOf(long accountId);

    void removeAllOf(long accountId);
}
