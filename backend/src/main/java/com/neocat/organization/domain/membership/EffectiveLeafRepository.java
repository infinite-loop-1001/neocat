package com.neocat.organization.domain.membership;

import java.util.Set;
import org.springframework.modulith.NamedInterface;

/**
 * 有效叶子权限快照（PRD 01 §6）。
 *
 * <p>有效叶子 = 用户直接加入的叶子 ∪ 用户加入的任意祖先节点的后代叶子。
 * 每次成员关系变更后立即重算。
 */
@NamedInterface("isOrganization")
public interface EffectiveLeafRepository {

    void replaceAll(long accountId, Set<Long> leafOrgIds);

    Set<Long> leavesOf(long accountId);

    Set<Long> membersOf(long orgId);

    void removeOrg(long orgId);
}
