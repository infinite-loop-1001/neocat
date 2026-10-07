package com.neocat.identity.domain.account;

import java.time.Instant;

/**
 * 账号聚合根。
 * PRD 01 §3.1：用户名一旦创建不可修改。
 */
@org.springframework.modulith.NamedInterface("identity")
@lombok.Getter
@lombok.EqualsAndHashCode
@lombok.ToString
public class Account {
    private final long id;

    private final String username;

    private final String passwordHash;

    private final Role role;

    private final AccountStatus status;

    private final boolean mustChangePassword;

    private final Instant createdAt;

    public Account(long id, String username, String passwordHash, Role role, AccountStatus status, boolean mustChangePassword, Instant createdAt) {
        this.id = id;
        this.username = username;
        this.passwordHash = passwordHash;
        this.role = role;
        this.status = status;
        this.mustChangePassword = mustChangePassword;
        this.createdAt = createdAt;
    }

    public Account withRole(Role newRole) {
        return new Account(id, username, passwordHash, newRole, status, mustChangePassword, createdAt);
    }
    public Account withStatus(AccountStatus newStatus) {
        return new Account(id, username, passwordHash, role, newStatus, mustChangePassword, createdAt);
    }
    public Account withPassword(String newHash, boolean requireChange) {
        return new Account(id, username, newHash, role, status, requireChange, createdAt);
    }
    public boolean enabled() {
        return status == AccountStatus.ENABLED;
    }
}




