package com.neocat.identity.infra.jdbc;

import com.neocat.identity.domain.account.Account;
import com.neocat.identity.domain.account.AccountRepository;
import com.neocat.identity.domain.account.AccountStatus;
import com.neocat.identity.domain.account.Role;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.Objects;
import java.util.List;

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
    public List<Account> findAll() {
        return mapper.selectAll().stream().map(this::toDomain).toList();
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

    private Account toDomain(AccountRow row) {
        return new Account(
                row.getId(),
                row.getUsername(),
                row.getPasswordHash(),
                Role.valueOf(row.getRole()),
                AccountStatus.valueOf(row.getStatus()),
                row.isMustChangePassword(),
                Objects.isNull(row.getCreatedAt()) ? Instant.EPOCH : row.getCreatedAt());
    }

    private AccountRow toRow(Account account) {
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