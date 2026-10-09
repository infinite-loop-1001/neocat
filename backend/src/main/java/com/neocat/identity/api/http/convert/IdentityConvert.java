package com.neocat.identity.api.http.convert;

import com.neocat.identity.api.http.dto.*;
import com.neocat.identity.domain.account.Account;
import com.neocat.identity.domain.auth.LoginResult;
import com.neocat.identity.domain.auth.LoginTarget;
import com.neocat.identity.api.http.dto.CurrentUser;
import com.neocat.identity.api.http.dto.Entry;
import com.neocat.identity.api.http.dto.LoginResponse;
import com.neocat.identity.api.http.dto.UserResponse;
import com.neocat.identity.api.http.dto.UserSummary;

public final class IdentityConvert {
    private IdentityConvert() {
    }

    public static LoginResponse login(LoginResult result, LoginTarget target) {
        Account account = result.getAccount();
        Entry entry = target.isServiceList()
                ? new Entry("SERVICE_LIST", null, null)
                : new Entry("SERVICE_TRANSACTION", target.getTargetService(), "TRANSACTION");
        return new LoginResponse(new UserSummary(account.getId(), account.getUsername(), account.getRole().name()),
                result.isMustChangePassword(), entry);
    }

    public static CurrentUser current(Account account) {
        return new CurrentUser(account.getId(), account.getUsername(), account.getRole().name(), account.isMustChangePassword());
    }

    public static UserResponse user(Account account) {
        return new UserResponse(account.getId(), account.getUsername(), account.getRole().name(),
                account.getStatus().name(), account.isMustChangePassword());
    }
}
