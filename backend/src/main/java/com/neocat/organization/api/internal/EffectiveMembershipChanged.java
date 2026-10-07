package com.neocat.organization.api.internal;

/** Synchronous update after an account's effective leaf permissions are recalculated. */
@lombok.Getter
@lombok.EqualsAndHashCode
@lombok.ToString
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
