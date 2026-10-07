package com.neocat.dashboard.domain.access;

/**
 * 组织权限查询（PRD 01 §7.2、PRD 05 §1）。
 *
 * <p>只有**叶子直接成员和祖先继承成员**可以查看/修改该叶子大盘。
 * 管理员角色**不提供组织权限旁路**。
 *
 * <p>由 isOrganization 模块实现，dashboard 只依赖抽象。
 */
@org.springframework.modulith.NamedInterface("dashboard")
public interface OrgAccessGateway {

    /** 是否为该组织的有效成员（直系或祖先继承）。 */
    boolean isEffectiveMember(long accountId, long orgId);

    /** 该组织是否为叶子。 */
    boolean isLeaf(long orgId);

    /** 该账号有权限的全部叶子组织（直系 + 祖先继承）。 */
    java.util.Set<Long> effectiveLeaves(long accountId);
}
