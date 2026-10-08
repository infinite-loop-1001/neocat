package com.neocat.organization.domain.lifecycle;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.ToString;
import org.springframework.modulith.NamedInterface;

/** Synchronous notification; listeners delete resources in the caller's MySQL transaction. */
@NamedInterface("isOrganization")
@Getter
@EqualsAndHashCode
@ToString
public class OrgDeletionRequested {
    private final long orgId;

    public OrgDeletionRequested(long orgId) {
        this.orgId = orgId;
    }
 }
