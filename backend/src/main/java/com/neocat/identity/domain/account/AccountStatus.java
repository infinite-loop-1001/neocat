package com.neocat.identity.domain.account;

/**
 * 账号状态。PRD 01 §3.4：禁用即时失效会话并移除告警收件人，保留组织成员关系。
 */
@org.springframework.modulith.NamedInterface("identity")
public enum AccountStatus {
    ENABLED,
    DISABLED
}
