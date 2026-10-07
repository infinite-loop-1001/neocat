package com.neocat.identity.domain.auth;

import com.neocat.identity.domain.account.Account;
import com.neocat.identity.domain.session.Session;

/**
 * 登录结果。
 *
 * @param session            新建会话
 * @param account            账号
 * @param mustChangePassword 是否必须先改密（PRD 01 §4.3）
 */
@org.springframework.modulith.NamedInterface("identity")
@lombok.Getter
@lombok.EqualsAndHashCode
@lombok.ToString
public class LoginResult {
    private final Session session;

    private final Account account;

    private final boolean mustChangePassword;

    public LoginResult(Session session, Account account, boolean mustChangePassword) {
        this.session = session;
        this.account = account;
        this.mustChangePassword = mustChangePassword;
    }

}
