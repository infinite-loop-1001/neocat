package com.neocat.identity.infra.adapter;

import com.neocat.identity.api.internal.AccountDirectory;
import com.neocat.platform.domain.init.SuperAdminProvisioner;
import org.springframework.stereotype.Component;

/** The identity module owns account creation; platform only owns the provisioning port. */
@Component
public class SuperAdminProvisioningAdapter implements SuperAdminProvisioner {
    private final AccountDirectory accounts;

    public SuperAdminProvisioningAdapter(AccountDirectory accounts) {
        this.accounts = accounts;
    }
    @Override
    public long createSuperAdmin(String username, String rawPassword) {
        return accounts.createInitialSuperAdmin(username, rawPassword);
    }
}
