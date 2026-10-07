package com.neocat.identity.domain.account;

/**
 * 系统角色。PRD 01 §3：管理员只能创建普通用户，只有超管可授予 ADMIN。
 */
@org.springframework.modulith.NamedInterface("identity")
public enum Role {
    USER,
    ADMIN,
    SUPER_ADMIN
}
