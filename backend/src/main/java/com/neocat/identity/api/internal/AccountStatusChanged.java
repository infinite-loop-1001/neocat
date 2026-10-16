package com.neocat.identity.api.internal;

import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.ToString;

/** 账号启停用的同步事件，供告警收件人维护使用。 */
@Getter
@EqualsAndHashCode
@ToString
public class AccountStatusChanged {

    /** 账号标识（登录名，形如 keith.ji） */
    private final String userAccount;

    /** true 表示启用，false 表示禁用 */
    private final boolean enabled;

    public AccountStatusChanged(String userAccount, boolean enabled) {
        this.userAccount = userAccount;
        this.enabled = enabled;
    }
 }
