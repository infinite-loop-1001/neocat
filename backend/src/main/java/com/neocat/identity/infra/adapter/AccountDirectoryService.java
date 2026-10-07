package com.neocat.identity.infra.adapter;

import com.neocat.identity.api.internal.AccountDirectory;
import com.neocat.identity.domain.account.AccountRepository;
import com.neocat.identity.domain.auth.PasswordHasher;
import com.neocat.identity.domain.account.Role;
import org.springframework.stereotype.Component;

import java.time.Clock;

@Component
public class AccountDirectoryService implements AccountDirectory {
    private final AccountRepository accounts;

    private final PasswordHasher passwords;

    private final Clock clock;

    public AccountDirectoryService(AccountRepository accounts, PasswordHasher passwords, Clock clock) {
        this.accounts = accounts;
        this.passwords = passwords;
        this.clock = clock;
    }
    @Override
    public boolean enabled(long accountId) {
        var account = accounts.findById(accountId);
        return java.util.Objects.nonNull(account) && account.enabled();
    }
    @Override
    public long createInitialSuperAdmin(String username, String rawPassword) {
        if (java.util.Objects.nonNull(accounts.findByUsername(username))) {
            throw new IllegalStateException("Initial super admin username already exists");
        }
        return accounts.create(username, passwords.hash(rawPassword), Role.SUPER_ADMIN, true, clock.instant()).getId();
    }
}
