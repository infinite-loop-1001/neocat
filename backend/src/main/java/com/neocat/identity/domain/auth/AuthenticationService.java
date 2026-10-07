package com.neocat.identity.domain.auth;

import com.neocat.identity.domain.account.Account;
import com.neocat.identity.domain.account.AccountRepository;
import com.neocat.identity.domain.session.Session;
import com.neocat.identity.domain.session.SessionRepository;

import com.neocat.common.error.exception.AuthenticationException;

import java.time.Instant;
import java.util.Optional;

import static com.neocat.common.error.ErrorCode.BAD_CREDENTIALS;

/**
 * 登录用例（PRD 01 §4.1）。
 *
 * <p>失败一律返回 {@link com.neocat.common.error.ErrorCode#BAD_CREDENTIALS}：
 * 不区分「账号不存在」「密码错误」「账号被禁用」，避免暴露账号是否存在。
 */
@org.springframework.stereotype.Service
@org.springframework.modulith.NamedInterface("identity")
public class AuthenticationService {

    private final AccountRepository accounts;

    private final SessionRepository sessions;

    private final PasswordHasher hasher;

    public AuthenticationService(AccountRepository accounts, SessionRepository sessions, PasswordHasher hasher) {
        this.accounts = accounts;
        this.sessions = sessions;
        this.hasher = hasher;
    }
    public LoginResult login(String username, String rawPassword, Instant at) {
        Account account = accounts.findByUsername(username);
        if (java.util.Objects.isNull(account)) {
            throw new AuthenticationException(BAD_CREDENTIALS);
        }
        if (!account.enabled()) {
            throw new AuthenticationException(BAD_CREDENTIALS);
        }
        if (!hasher.matches(rawPassword, account.getPasswordHash())) {
            throw new AuthenticationException(BAD_CREDENTIALS);
        }
        Session session = sessions.create(account.getId(), at);
        return new LoginResult(session, account, account.isMustChangePassword());
    }
}
