package com.neocat.alert.domain.recipient;

/**
 * 账号与组织资格查询（PRD 06 §9）。
 *
 * <p>由 identity / isOrganization 模块实现；alert 只依赖该抽象。
 */
@org.springframework.modulith.NamedInterface("alert")
public interface RecipientGateway {

    /** 账号是否启用（禁用账号不可作为收件人）。 */
    boolean isEnabled(long accountId);

    /** 账号是否为该叶子的有效成员（直系或祖先继承）。 */
    boolean isEffectiveMember(long accountId, long orgId);
}
