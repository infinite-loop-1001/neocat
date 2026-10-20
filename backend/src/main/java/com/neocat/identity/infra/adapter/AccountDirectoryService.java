package com.neocat.identity.infra.adapter;

import com.neocat.common.time.clock.TimeProvider;

import com.neocat.identity.api.internal.AccountDirectory;
import com.neocat.identity.domain.account.AccountRepository;
import com.neocat.identity.domain.auth.PasswordHasher;
import com.neocat.identity.domain.account.Role;
import org.springframework.stereotype.Component;
import java.util.Objects;

@Component
public class AccountDirectoryService implements AccountDirectory {
    private final AccountRepository accounts;

    private final PasswordHasher passwords;

    public AccountDirectoryService(AccountRepository accounts, PasswordHasher passwords) {
        this.accounts = accounts;
        this.passwords = passwords;
    }

    @Override
    public boolean exists(String userAccount) {
        return Objects.nonNull(accounts.findByUserAccount(userAccount));
    }

    @Override
    public boolean enabled(String userAccount) {
        var account = accounts.findByUserAccount(userAccount);
        return Objects.nonNull(account) && account.enabled();
    }
    @Override
    public String createInitialSuperAdmin(String userAccount, String rawPassword) {
        if (Objects.nonNull(accounts.findByUserAccount(userAccount))) {
            throw new IllegalStateException("Initial super admin username already exists");
        }
        return accounts.create(userAccount, passwords.hash(rawPassword), Role.SUPER_ADMIN, true, TimeProvider.now())
                .getUserAccount();
    }
}
