package com.neocat.organization.domain.membership;

import java.util.Set;
import org.springframework.modulith.NamedInterface;

/**
 * 有效叶子权限快照（PRD 01 §6）。
 *
 * <p>有效叶子 = 用户直接加入的叶子 ∪ 用户加入的任意祖先节点的后代叶子。
 * 每次成员关系变更后立即重算。
 */
@NamedInterface("organization")
public interface EffectiveLeafRepository {

    /** 覆盖该账号的有效叶子集合；userAccount 为账号标识（登录名）。 */
    void replaceAll(String userAccount, Set<Long> leafOrgIds);

    Set<Long> leavesOf(String userAccount);

    /** 该叶子上的有效成员账号标识集合（含祖先继承）。 */
    Set<String> membersOf(long orgId);

    void removeOrg(long orgId);
}
