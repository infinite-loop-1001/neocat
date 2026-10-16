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
import java.util.Objects;

import static com.neocat.common.error.ErrorCode.BAD_CREDENTIALS;
import static com.neocat.common.error.ErrorCode.CANNOT_MODIFY_SELF;
import static com.neocat.common.error.ErrorCode.FORBIDDEN;
import static com.neocat.common.error.ErrorCode.PASSWORD_TOO_SHORT;
import static com.neocat.common.error.ErrorCode.PASSWORD_UNCHANGED;
import static com.neocat.common.error.ErrorCode.USER_EXISTS;
import static com.neocat.common.error.ErrorCode.USER_NOT_FOUND;
import com.neocat.common.locking.MySqlLocked;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.modulith.NamedInterface;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 账号生命周期用例（PRD 01 §3）。
 */
@Service
@NamedInterface("identity")
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
    @Autowired
    public AccountService(AccountRepository accounts, SessionRepository sessions, PasswordHasher hasher,
                          AccessHistoryRepository accessHistory, ApplicationEventPublisher events) {
        this.accounts = accounts;
        this.sessions = sessions;
        this.hasher = hasher;
        this.accessHistory = accessHistory;
        this.events = events;
    }
    /** §3.1 创建普通账号：密码 ≥ 8 位、用户名唯一、标记首次登录必须改密。 */
    @MySqlLocked("metadata")
    public Account create(String userAccount, String rawPassword, Instant at) {
        requirePasswordLength(rawPassword);
        if (Objects.nonNull(accounts.findByUserAccount(userAccount))) {
            throw new ConflictException(USER_EXISTS, userAccount);
        }
        return accounts.create(userAccount, hasher.hash(rawPassword), Role.USER, true, at);
    }
    /** §3.1 管理员创建账号：只能创建普通用户，不能经此路径绕过授予 ADMIN 的规则。 */
    @MySqlLocked("metadata")
    public Account createAsAdmin(String userAccount, String rawPassword, Role targetRole, Instant at) {
        if (!Objects.equals(targetRole, Role.USER)) {
            throw new AuthorizationException(FORBIDDEN, "管理员只能创建普通用户；授予 ADMIN 仅限超级管理员");
        }
        return create(userAccount, rawPassword, at);
    }
    /** §3.2 授予/取消 ADMIN：仅超管可执行。 */
    @MySqlLocked("metadata")
    public Account changeRole(String userAccount, Role targetRole, Role actorRole) {
        return changeRole(userAccount, targetRole, actorRole, null);
    }
    /**
     * §3.2 带操作者身份的变更。
     *
     * @param actorUserAccount 操作者的账号标识；传 {@code null} 表示不校验「不能修改自己」
     */
    @MySqlLocked("metadata")
    public Account changeRole(String userAccount, Role targetRole, Role actorRole, String actorUserAccount) {
        if (!Objects.equals(actorRole, Role.SUPER_ADMIN)) {
            throw new AuthorizationException(FORBIDDEN, "只有超级管理员可以授予或取消 ADMIN");
        }
        if (Objects.nonNull(actorUserAccount) && Objects.equals(actorUserAccount, userAccount)) {
            throw new AuthorizationException(CANNOT_MODIFY_SELF);
        }
        Account account = requireAccount(userAccount);
        return accounts.save(account.withRole(targetRole));
    }
    /** §3.3 重置密码：置强制改密 + 吊销该账号全部会话。 */
    @MySqlLocked("metadata")
    public Account resetPassword(String userAccount, String newRawPassword, Instant at) {
        requirePasswordLength(newRawPassword);
        Account account = requireAccount(userAccount);
        Account updated = accounts.save(account.withPassword(hasher.hash(newRawPassword), true));
        sessions.invalidateAllOf(userAccount);
        return updated;
    }
    /**
     * §3.4 禁用：吊销全部会话、从告警收件人中移除、保留组织直接成员关系。
     *
     * <p>「从收件人移除」由 {@code AccountStatusChanged} 的订阅方（alert 模块）完成，
     * 本用例只负责账号状态与会话，并在结果中声明副作用语义。
     */
    @Transactional
    @MySqlLocked("metadata")
    public AccountChangeResult disable(String userAccount, Instant at) {
        Account account = requireAccount(userAccount);
        Account updated = accounts.save(account.withStatus(AccountStatus.DISABLED));
        sessions.invalidateAllOf(userAccount);
        if (Objects.nonNull(events)) {
            events.publishEvent(new AccountStatusChanged(userAccount, false));
        }
        return new AccountChangeResult(updated, true, false);
    }
    /** §3.4 启用：可重新登录、恢复组织成员继承资格、不恢复任何告警收件关系。 */
    @Transactional
    @MySqlLocked("metadata")
    public AccountChangeResult enable(String userAccount, Instant at) {
        Account account = requireAccount(userAccount);
        Account updated = accounts.save(account.withStatus(AccountStatus.ENABLED));
        // rules: 不允许 if 和内部执行语句没有 {} 包裹, 且在同一行
        if (Objects.nonNull(events)) events.publishEvent(new AccountStatusChanged(userAccount, true));
        return new AccountChangeResult(updated, true, false);
    }
    /** §4.3 首次改密 / 重置后改密：新旧不同、新密码 ≥ 8 位、成功后清除标记。 */
    @MySqlLocked("metadata")
    public Account changePassword(String userAccount, String oldRawPassword, String newRawPassword, Instant at) {
        Account account = requireAccount(userAccount);
        if (!hasher.matches(oldRawPassword, account.getPasswordHash())) {
            throw new AuthenticationException(BAD_CREDENTIALS);
        }
        if (Objects.equals(oldRawPassword, newRawPassword)) {
            throw new ValidationException(PASSWORD_UNCHANGED);
        }
        requirePasswordLength(newRawPassword);
        return accounts.save(account.withPassword(hasher.hash(newRawPassword), false));
    }
    /** §4.1 登录落点：有可用最近访问服务 → 该服务 Transaction；否则 → 服务列表。 */
    public LoginTarget resolveLoginTarget(String userAccount, ServiceAvailability availability) {
        for (String service : accessHistory.recentServices(userAccount, 10)) {
            if (availability.hasData(service)) {
                return new LoginTarget(service);
            }
        }
        return LoginTarget.SERVICE_LIST;
    }
    /** §4.1 记录一次服务访问，用于下次登录落点。 */
    public void recordAccess(String userAccount, String serviceName, Instant at) {
        accessHistory.record(userAccount, serviceName, at);
    }

    // ── 内部 ─────────────────────────────────────────────────

    private void requirePasswordLength(String rawPassword) {
        if (Objects.isNull(rawPassword) || rawPassword.length() < MIN_PASSWORD_LENGTH) {
            throw new ValidationException(PASSWORD_TOO_SHORT, MIN_PASSWORD_LENGTH);
        }
    }
    private Account requireAccount(String userAccount) {
        Account found = accounts.findByUserAccount(userAccount);
        if (Objects.isNull(found)) {
            throw new ResourceNotFoundException(USER_NOT_FOUND, userAccount);
        }
        return found;
    }
}
