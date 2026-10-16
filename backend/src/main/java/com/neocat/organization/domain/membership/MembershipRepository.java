package com.neocat.organization.domain.membership;

import java.util.Set;
import org.springframework.modulith.NamedInterface;

/**
 * 成员关系仓库（直接成员）。
 */
@NamedInterface("organization")
public interface MembershipRepository {

    /** 加入直接成员关系；userAccount 为账号标识（登录名）。 */
    void add(long orgId, String userAccount);

    void remove(long orgId, String userAccount);

    /** 该节点的直接成员账号标识集合。 */
    Set<String> membersOf(long orgId);

    /** 该账号直接加入的节点集合。 */
    Set<Long> orgsOf(String userAccount);

    void removeAllOf(String userAccount);
}
