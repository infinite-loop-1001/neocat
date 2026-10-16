package com.neocat.alert.infra.adapter;

import com.neocat.alert.domain.gateway.RecipientGateway;
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
    public boolean isEnabled(String userAccount) {
        return accounts.enabled(userAccount);
    }
    @Override
    public boolean isEffectiveMember(String userAccount, long orgId) {
        return organizations.isEffectiveMember(userAccount, orgId);
    }
}
