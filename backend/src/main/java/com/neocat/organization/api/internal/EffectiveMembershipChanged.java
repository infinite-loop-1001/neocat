package com.neocat.organization.api.internal;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.ToString;

/** 账号有效叶子权限重算后的同步事件；每条变化单独发布。 */
@Getter
@EqualsAndHashCode
@ToString
public class EffectiveMembershipChanged {
    /** 账号标识（登录名，形如 keith.ji） */
    private final String userAccount;

    /** 叶子组织 ID */
    private final Long orgId;

    /** true 表示获得该叶子权限，false 表示失去 */
    private final boolean granted;

    public EffectiveMembershipChanged(String userAccount, Long orgId, boolean granted) {
        this.userAccount = userAccount;
        this.orgId = orgId;
        this.granted = granted;
    }
 }
