package com.neocat.identity.api.internal;

import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.ToString;

/** Synchronous account status change for recipient maintenance. */
@Getter
@EqualsAndHashCode
@ToString
public class AccountStatusChanged {

    private final long accountId;

    private final boolean enabled;

    public AccountStatusChanged(long accountId, boolean enabled) {
        this.accountId = accountId;
        this.enabled = enabled;
    }
 }
