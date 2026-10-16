package com.neocat.identity.domain.account;

import java.time.Instant;
import java.util.List;
import org.springframework.lang.Nullable;
import org.springframework.modulith.NamedInterface;

@NamedInterface("identity")

public interface AccountRepository {

    /**
     * 按业务标识查询账号。
     *
     * <p>账号的业务标识就是登录名（形如 {@code keith.ji}）；一期不允许改名，
     * 因此它可以安全地充当跨模块引用账号的唯一句柄。数字主键只属于存储层，
     * 业务代码不得用它引用账号——可读性差且随数据迁移失效。
     */
    @Nullable
    Account findByUserAccount(String userAccount);

    /** 全部账号（管理页列表用）。按用户名升序，保证输出稳定。 */
    List<Account> findAll();

    Account save(Account account);

    /**
     * 由仓库负责主键分配与初始状态标记。
     *
     * @param mustChangePassword 首次创建与密码重置后为 true
     */
    Account create(String userAccount, String passwordHash, Role role, boolean mustChangePassword, Instant at);
}
