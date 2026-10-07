package com.neocat.alert.infra.adapter;

import com.neocat.alert.domain.recipient.RecipientGateway;
import com.neocat.identity.api.internal.AccountDirectory;
import com.neocat.organization.api.internal.OrganizationAccess;
import org.springframework.stereotype.Component;

@Component
public class RecipientEligibilityAdapter implements RecipientGateway {
    private final AccountDirectory accounts;

    private final OrganizationAccess organizations;

    public RecipientEligibilityAdapter(AccountDirectory accounts, OrganizationAccess organizations) {
        this.accounts = accounts;
        this.organizations = organizations;
    }
    @Override
    public boolean isEnabled(long accountId) {
        return accounts.enabled(accountId);
    }
    @Override
    public boolean isEffectiveMember(long accountId, long orgId) {
        return organizations.isEffectiveMember(accountId, orgId);
    }
}
