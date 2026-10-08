package com.neocat.identity.domain.account;

import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.ToString;
import org.springframework.modulith.NamedInterface;

/**
 * 账号变更结果。禁用/启用会牵动会话与告警收件人，这里显式表达各副作用的处理方式，
 * 供上层（web / 事件订阅者）决策。
 * // fixme: param 找不到
 * @param account              变更后的账号
 * @param preservedMemberships 组织直接成员关系是否保留（PRD 01 §3.4）
 * @param recipientsRestored   告警收件关系是否被恢复（PRD 01 §3.4：启用后不恢复）
 */
@NamedInterface("identity")
@Getter
@EqualsAndHashCode
@ToString
public class AccountChangeResult {
    private final Account account;

    private final boolean preservedMemberships;

    private final boolean recipientsRestored;

    public AccountChangeResult(Account account, boolean preservedMemberships, boolean recipientsRestored) {
        this.account = account;
        this.preservedMemberships = preservedMemberships;
        this.recipientsRestored = recipientsRestored;
    }

    public AccountStatus status() {
        return account.getStatus();
    }
}
