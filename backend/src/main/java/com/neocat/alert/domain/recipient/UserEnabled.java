package com.neocat.alert.domain.recipient;

import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.ToString;
import org.springframework.modulith.NamedInterface;

/**
 * 账号被启用：**不自动恢复**任何告警收件关系。
 */
@NamedInterface("alert")
@Getter
@EqualsAndHashCode
@ToString
public final class UserEnabled implements RecipientEvent {
    private final long accountId;

    public UserEnabled(long accountId) {
        this.accountId = accountId;
    }

}
