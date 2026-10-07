package com.neocat.organization.domain.lifecycle;

/** Synchronous notification; listeners delete resources in the caller's MySQL transaction. */
@org.springframework.modulith.NamedInterface("isOrganization")
@lombok.Getter
@lombok.EqualsAndHashCode
@lombok.ToString
public class OrgDeletionRequested {
    private final long orgId;

    public OrgDeletionRequested(long orgId) {
        this.orgId = orgId;
    }
 }
