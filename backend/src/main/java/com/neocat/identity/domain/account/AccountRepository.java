package com.neocat.identity.domain.account;

import java.time.Instant;
import java.util.Optional;

@org.springframework.modulith.NamedInterface("identity")

public interface AccountRepository {

    @org.springframework.lang.Nullable
    Account findById(long id);

    @org.springframework.lang.Nullable
    Account findByUsername(String username);

    /** 全部账号（管理页列表用）。按用户名升序，保证输出稳定。 */
    java.util.List<Account> findAll();

    Account save(Account account);

    /**
     * 由仓库负责主键分配与初始状态标记。
     *
     * @param mustChangePassword 首次创建与密码重置后为 true
     */
    Account create(String username, String passwordHash, Role role, boolean mustChangePassword, Instant at);
}
