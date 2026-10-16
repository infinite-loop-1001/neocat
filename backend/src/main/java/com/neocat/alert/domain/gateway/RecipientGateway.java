package com.neocat.alert.domain.gateway;
import org.springframework.modulith.NamedInterface;

/**
 * 账号与组织资格查询（PRD 06 §9）。
 *
 * <p>由 identity / isOrganization 模块实现；alert 只依赖该抽象。
 */
@NamedInterface("alert")
public interface RecipientGateway {

    /** 账号是否为启用状态。 */
    boolean isEnabled(String userAccount);

    /** 账号是否为该叶子的有效成员（直系或祖先继承）。 */
    boolean isEffectiveMember(String userAccount, long orgId);
}
