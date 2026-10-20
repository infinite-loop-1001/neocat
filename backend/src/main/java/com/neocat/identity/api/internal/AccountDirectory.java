package com.neocat.identity.api.internal;

/** Minimum account capabilities used by other modules. */
public interface AccountDirectory {
    /** 账号是否存在；账号禁用不影响其作为组织成员标识，启用后恢复组织继承权限。 */
    boolean exists(String userAccount);

    /** 账号是否处于启用状态；账号不存在按未启用处理。 */
    boolean enabled(String userAccount);

    /** 创建首个超级管理员；用户名已存在时拒绝。 */
    String createInitialSuperAdmin(String userAccount, String rawPassword);
}
