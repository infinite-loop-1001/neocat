package com.neocat.identity.api.internal;

/** Minimum account capabilities used by other modules. */
public interface AccountDirectory {
    /** 账号是否处于启用状态；账号不存在按未启用处理。 */
    boolean enabled(String userAccount);

    /** 创建首个超级管理员；用户名已存在时拒绝。 */
    String createInitialSuperAdmin(String userAccount, String rawPassword);
}
