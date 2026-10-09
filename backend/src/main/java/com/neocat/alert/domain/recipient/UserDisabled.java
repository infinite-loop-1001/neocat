package com.neocat.alert.domain.recipient;

import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.ToString;
import org.springframework.modulith.NamedInterface;

/**
 * 账号被禁用：从所有相关规则接收人中移除，规则不因此关闭。
 */
@NamedInterface("alert")
@Getter
@EqualsAndHashCode
@ToString
public final class UserDisabled implements RecipientEvent {

    private final long accountId;

    public UserDisabled(long accountId) {
        this.accountId = accountId;
    }

}
