package com.neocat.identity.domain.auth;

import com.neocat.identity.domain.account.Account;
import com.neocat.identity.domain.account.AccountRepository;
import com.neocat.identity.domain.session.Session;
import com.neocat.identity.domain.session.SessionRepository;

import com.neocat.common.error.ErrorCode;
import com.neocat.common.error.NeocatException;
import com.neocat.common.error.exception.AuthenticationException;
import com.neocat.common.error.exception.AuthorizationException;

import java.time.Instant;
import java.util.Set;
import java.util.Objects;
import org.springframework.modulith.NamedInterface;
import org.springframework.stereotype.Service;

/**
 * 会话守卫（PRD 01 §4.2 / §4.3）。
 *
 * <p>规则：
 * <ul>
 *   <li>未登录不能访问任何报表、管理或组织资源。</li>
 *   <li>强制改密会话只允许改密、登出、查询自身。</li>
 *   <li>每次有效请求为会话续期。</li>
 * </ul>
 */
@Service
@NamedInterface("identity")
public class SessionGuard {

    /** 强制改密会话允许访问的端点。 */
    private static final Set<String> PASSWORD_CHANGE_ALLOWED = Set.of(
            "POST /api/me/password",
            "POST /api/logout",
            "GET /api/me");

    private final AccountRepository accounts;

    private final SessionRepository sessions;

    public SessionGuard(AccountRepository accounts, SessionRepository sessions) {
        this.accounts = accounts;
        this.sessions = sessions;
    }
    /**
     * @return {@code null} 表示放行；否则为应返回的错误
     */
    public NeocatException check(String sessionId, String endpoint, Instant at) {
        Session session = findValidSession(sessionId, at);
        if (Objects.isNull(session)) {
            return new AuthenticationException(ErrorCode.UNAUTHENTICATED);
        }
        Account account = accounts.findById(session.getAccountId());
        if (Objects.isNull(account) || !account.enabled()) {
            sessions.invalidate(sessionId);
            return new AuthenticationException(ErrorCode.UNAUTHENTICATED);
        }
        if (account.isMustChangePassword() && !PASSWORD_CHANGE_ALLOWED.contains(endpoint)) {
            return new AuthorizationException(ErrorCode.PASSWORD_CHANGE_REQUIRED);
        }
        // 有效请求为会话续期（创建会话的请求不在此处，故至少过期时间不早于原值）
        sessions.touch(sessionId, at);
        return null;
    }
    /**
     * 校验会话有效性并返回账号，供需要当前用户的调用方使用。
     */
    public Account requireSession(String sessionId, Instant at) {
        Session session = findValidSession(sessionId, at);
        if (Objects.isNull(session)) {
            throw new AuthenticationException(ErrorCode.UNAUTHENTICATED);
        }
        Account account = accounts.findById(session.getAccountId());
        if (Objects.isNull(account) || !account.enabled()) {
            throw new AuthenticationException(ErrorCode.UNAUTHENTICATED);
        }
        sessions.touch(sessionId, at);
        return account;
    }
    private Session findValidSession(String sessionId, Instant at) {
        if (Objects.isNull(sessionId) || !sessions.isValid(sessionId, at)) {
            return null;
        }
        // 从持久化仓储按会话 ID 读取账号归属
        return sessions.findSession(sessionId);
    }
}
