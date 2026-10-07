package com.neocat.identity.infra.jdbc;

import com.neocat.identity.domain.account.Account;
import com.neocat.identity.domain.account.AccountRepository;
import com.neocat.identity.domain.account.AccountStatus;
import com.neocat.identity.domain.account.Role;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.Objects;

/**
 * 账号仓储的 MyBatis 适配器（表 {@code nc_account}）。
 *
 * <p>由 {@code AccountMapper} 承载 SQL；本类只做「领域对象 ↔ 行记录」转换，
 * 使领域层的 {@link Account} 保持对 MyBatis 的零依赖。
 *
 * <p>按项目纪律，Mapper 的连通性（能否真正从 MySQL 查到数据）不在单测范围，
 * 由人工接通中间件后验证。
 */
@Repository
public class AccountRepositoryAdapter implements AccountRepository {

    private final AccountMapper mapper;

    public AccountRepositoryAdapter(AccountMapper mapper) {
        this.mapper = mapper;
    }
    @Override
    public Account findById(long id) {
        var row = mapper.selectById(id);
        return Objects.isNull(row) ? null : toDomain(row);
    }
    @Override
    public Account findByUsername(String username) {
        var row = mapper.selectByUsername(username);
        return Objects.isNull(row) ? null : toDomain(row);
    }
    @Override
    public java.util.List<Account> findAll() {
        return mapper.selectAll().stream().map(AccountRepositoryAdapter::toDomain).toList();
    }
    @Override
    public Account save(Account account) {
        AccountRow row = toRow(account);
        mapper.update(row);
        return account;
    }
    @Override
    public Account create(String username, String passwordHash, Role role,
                          boolean mustChangePassword, Instant at) {
        AccountRow row = new AccountRow();
        row.setUsername(username);
        row.setPasswordHash(passwordHash);
        row.setRole(role.name());
        row.setStatus(AccountStatus.ENABLED.name());
        row.setMustChangePassword(mustChangePassword);
        mapper.insert(row);
        return toDomain(row);
    }

    // ── 行记录 ───────────────────────────────────────────────

    /** 数据库行映射（字段名与 nc_account 列一致）。 */
    public static class AccountRow {
        private Long id;

        private String username;

        private String passwordHash;

        private String role;

        private String status;

        private boolean mustChangePassword;

        private Instant createdAt;

        private Instant updatedAt;

        public Long getId() {
            return id;
        }

        public void setId(Long id) {
            this.id = id;
        }

        public String getUsername() {
            return username;
        }

        public void setUsername(String username) {
            this.username = username;
        }

        public String getPasswordHash() {
            return passwordHash;
        }

        public void setPasswordHash(String passwordHash) {
            this.passwordHash = passwordHash;
        }

        public String getRole() {
            return role;
        }

        public void setRole(String role) {
            this.role = role;
        }

        public String getStatus() {
            return status;
        }

        public void setStatus(String status) {
            this.status = status;
        }

        public boolean isMustChangePassword() {
            return mustChangePassword;
        }

        public void setMustChangePassword(boolean mustChangePassword) {
            this.mustChangePassword = mustChangePassword;
        }

        public Instant getCreatedAt() {
            return createdAt;
        }

        public void setCreatedAt(Instant createdAt) {
            this.createdAt = createdAt;
        }

        public Instant getUpdatedAt() {
            return updatedAt;
        }

        public void setUpdatedAt(Instant updatedAt) {
            this.updatedAt = updatedAt;
        }
    }
    private static Account toDomain(AccountRow row) {
        return new Account(
                row.getId(),
                row.getUsername(),
                row.getPasswordHash(),
                Role.valueOf(row.getRole()),
                AccountStatus.valueOf(row.getStatus()),
                row.isMustChangePassword(),
                Objects.isNull(row.getCreatedAt()) ? Instant.EPOCH : row.getCreatedAt());
    }
    private static AccountRow toRow(Account account) {
        AccountRow row = new AccountRow();
        row.setId(account.getId());
        row.setUsername(account.getUsername());
        row.setPasswordHash(account.getPasswordHash());
        row.setRole(account.getRole().name());
        row.setStatus(account.getStatus().name());
        row.setMustChangePassword(account.isMustChangePassword());
        return row;
    }
}




