package com.neocat.identity.domain.account;
import org.springframework.modulith.NamedInterface;

/**
 * 账号状态。PRD 01 §3.4：禁用即时失效会话并移除告警收件人，保留组织成员关系。
 */
@NamedInterface("identity")
public enum AccountStatus {
    ENABLED,
    DISABLED
}
