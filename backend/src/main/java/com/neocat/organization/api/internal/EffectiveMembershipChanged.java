package com.neocat.organization.api.internal;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.ToString;

/** Synchronous update after an account's effective leaf permissions are recalculated. */
@Getter
@EqualsAndHashCode
@ToString
public class EffectiveMembershipChanged {
    private final long accountId;

    private final long orgId;

    private final boolean granted;

    public EffectiveMembershipChanged(long accountId, long orgId, boolean granted) {
        this.accountId = accountId;
        this.orgId = orgId;
        this.granted = granted;
    }
 }
