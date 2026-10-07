package com.neocat.identity.domain.account;

import com.neocat.identity.domain.auth.LoginTarget;
import com.neocat.identity.domain.auth.PasswordHasher;
import com.neocat.identity.domain.auth.ServiceAvailability;
import com.neocat.identity.domain.session.AccessHistoryRepository;
import com.neocat.identity.domain.session.SessionRepository;

import com.neocat.common.error.exception.AuthenticationException;
import com.neocat.common.error.exception.AuthorizationException;
import com.neocat.common.error.exception.ConflictException;
import com.neocat.common.error.exception.ResourceNotFoundException;
import com.neocat.common.error.exception.ValidationException;
import com.neocat.identity.api.internal.AccountStatusChanged;
import org.springframework.context.ApplicationEventPublisher;

import java.time.Instant;
import java.util.Optional;

import static com.neocat.common.error.ErrorCode.BAD_CREDENTIALS;
import static com.neocat.common.error.ErrorCode.CANNOT_MODIFY_SELF;
import static com.neocat.common.error.ErrorCode.FORBIDDEN;
import static com.neocat.common.error.ErrorCode.PASSWORD_TOO_SHORT;
import static com.neocat.common.error.ErrorCode.PASSWORD_UNCHANGED;
import static com.neocat.common.error.ErrorCode.USER_EXISTS;
import static com.neocat.common.error.ErrorCode.USER_NOT_FOUND;

/**
 * 账号生命周期用例（PRD 01 §3）。
 */
@org.springframework.stereotype.Service
@org.springframework.modulith.NamedInterface("identity")
public class AccountService {

    static final int MIN_PASSWORD_LENGTH = 8;

    private final AccountRepository accounts;

    private final SessionRepository sessions;

    private final PasswordHasher hasher;

    private final AccessHistoryRepository accessHistory;

    private final ApplicationEventPublisher events;

    public AccountService(AccountRepository accounts, SessionRepository sessions, PasswordHasher hasher,
                           AccessHistoryRepository accessHistory) {
        this(accounts, sessions, hasher, accessHistory, null);
    }
    @org.springframework.beans.factory.annotation.Autowired
    public AccountService(AccountRepository accounts, SessionRepository sessions, PasswordHasher hasher,
                          AccessHistoryRepository accessHistory, ApplicationEventPublisher events) {
        this.accounts = accounts;
        this.sessions = sessions;
        this.hasher = hasher;
        this.accessHistory = accessHistory;
        this.events = events;
    }
    /** §3.1 创建普通账号：密码 ≥ 8 位、用户名唯一、标记首次登录必须改密。 */
    @com.neocat.common.locking.MySqlLocked("metadata")
    public Account create(String username, String rawPassword, Instant at) {
        requirePasswordLength(rawPassword);
        if (java.util.Objects.nonNull(accounts.findByUsername(username))) {
            throw new ConflictException(USER_EXISTS, username);
        }
        return accounts.create(username, hasher.hash(rawPassword), Role.USER, true, at);
    }
    /** §3.1 管理员创建账号：只能创建普通用户，不能经此路径绕过授予 ADMIN 的规则。 */
    @com.neocat.common.locking.MySqlLocked("metadata")
    public Account createAsAdmin(String username, String rawPassword, Role targetRole, Instant at) {
        if (targetRole != Role.USER) {
            throw new AuthorizationException(FORBIDDEN, "管理员只能创建普通用户；授予 ADMIN 仅限超级管理员");
        }
        return create(username, rawPassword, at);
    }
    /** §3.2 授予/取消 ADMIN：仅超管可执行。 */
    @com.neocat.common.locking.MySqlLocked("metadata")
    public Account changeRole(long accountId, Role targetRole, Role actorRole) {
        return changeRole(accountId, targetRole, actorRole, NO_ACTOR);
    }
    /**
     * §3.2 带操作者身份的变更。
     *
     * @param actorAccountId 操作者账号 ID；传 {@link #NO_ACTOR} 表示不校验「不能修改自己」
     */
    @com.neocat.common.locking.MySqlLocked("metadata")
    public Account changeRole(long accountId, Role targetRole, Role actorRole, long actorAccountId) {
        if (actorRole != Role.SUPER_ADMIN) {
            throw new AuthorizationException(FORBIDDEN, "只有超级管理员可以授予或取消 ADMIN");
        }
        if (actorAccountId != NO_ACTOR && actorAccountId == accountId) {
            throw new AuthorizationException(CANNOT_MODIFY_SELF);
        }
        Account account = requireAccount(accountId);
        return accounts.save(account.withRole(targetRole));
    }
    /** §3.3 重置密码：置强制改密 + 吊销该账号全部会话。 */
    @com.neocat.common.locking.MySqlLocked("metadata")
    public Account resetPassword(long accountId, String newRawPassword, Instant at) {
        requirePasswordLength(newRawPassword);
        Account account = requireAccount(accountId);
        Account updated = accounts.save(account.withPassword(hasher.hash(newRawPassword), true));
        sessions.invalidateAllOf(accountId);
        return updated;
    }
    /**
     * §3.4 禁用：吊销全部会话、从告警收件人中移除、保留组织直接成员关系。
     *
     * <p>「从收件人移除」由 {@code AccountStatusChanged} 的订阅方（alert 模块）完成，
     * 本用例只负责账号状态与会话，并在结果中声明副作用语义。
     */
    @org.springframework.transaction.annotation.Transactional
    @com.neocat.common.locking.MySqlLocked("metadata")
    public AccountChangeResult disable(long accountId, Instant at) {
        Account account = requireAccount(accountId);
        Account updated = accounts.save(account.withStatus(AccountStatus.DISABLED));
        sessions.invalidateAllOf(accountId);
        if (events != null) events.publishEvent(new AccountStatusChanged(accountId, false));
        return new AccountChangeResult(updated, true, false);
    }
    /** §3.4 启用：可重新登录、恢复组织成员继承资格、不恢复任何告警收件关系。 */
    @org.springframework.transaction.annotation.Transactional
    @com.neocat.common.locking.MySqlLocked("metadata")
    public AccountChangeResult enable(long accountId, Instant at) {
        Account account = requireAccount(accountId);
        Account updated = accounts.save(account.withStatus(AccountStatus.ENABLED));
        if (events != null) events.publishEvent(new AccountStatusChanged(accountId, true));
        return new AccountChangeResult(updated, true, false);
    }
    /** §4.3 首次改密 / 重置后改密：新旧不同、新密码 ≥ 8 位、成功后清除标记。 */
    @com.neocat.common.locking.MySqlLocked("metadata")
    public Account changePassword(long accountId, String oldRawPassword, String newRawPassword, Instant at) {
        Account account = requireAccount(accountId);
        if (!hasher.matches(oldRawPassword, account.getPasswordHash())) {
            throw new AuthenticationException(BAD_CREDENTIALS);
        }
        if (oldRawPassword.equals(newRawPassword)) {
            throw new ValidationException(PASSWORD_UNCHANGED);
        }
        requirePasswordLength(newRawPassword);
        return accounts.save(account.withPassword(hasher.hash(newRawPassword), false));
    }
    /** §4.1 登录落点：有可用最近访问服务 → 该服务 Transaction；否则 → 服务列表。 */
    public LoginTarget resolveLoginTarget(long accountId, ServiceAvailability availability) {
        for (String service : accessHistory.recentServices(accountId, 10)) {
            if (availability.hasData(service)) {
                return new LoginTarget(service);
            }
        }
        return LoginTarget.SERVICE_LIST;
    }
    /** §4.1 记录一次服务访问，用于下次登录落点。 */
    public void recordAccess(long accountId, String serviceName, Instant at) {
        accessHistory.record(accountId, serviceName, at);
    }

    // ── 内部 ─────────────────────────────────────────────────

    private static final long NO_ACTOR = -1L;

    private void requirePasswordLength(String rawPassword) {
        if (rawPassword == null || rawPassword.length() < MIN_PASSWORD_LENGTH) {
            throw new ValidationException(PASSWORD_TOO_SHORT, MIN_PASSWORD_LENGTH);
        }
    }
    private Account requireAccount(long accountId) {
        Account found = accounts.findById(accountId);
        if (java.util.Objects.isNull(found)) {
            throw new ResourceNotFoundException(USER_NOT_FOUND, accountId);
        }
        return found;
    }
}

