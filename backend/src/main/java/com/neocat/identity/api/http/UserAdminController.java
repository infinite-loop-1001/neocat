package com.neocat.identity.api.http;

import com.neocat.identity.api.http.dto.IdentityDtos.*;
import com.neocat.identity.api.http.convert.IdentityConvert;

import com.neocat.common.error.exception.AuthorizationException;
import com.neocat.common.error.ErrorCode;
import com.neocat.identity.domain.account.Account;
import com.neocat.identity.domain.account.AccountRepository;
import com.neocat.identity.domain.account.AccountService;
import com.neocat.identity.domain.account.Role;
import com.neocat.identity.api.http.auth.SessionInterceptor;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Clock;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 账号管理 HTTP 入口。 */
@RestController
@RequestMapping("/api")
public class UserAdminController {
    private final AccountService accounts;

    private final AccountRepository repository;

    private final Clock clock;

    public UserAdminController(AccountService accounts, AccountRepository repository, Clock clock) {
        this.accounts = accounts;
        this.repository = repository;
        this.clock = clock;
    }
    @GetMapping("/users")
    public ResponseEntity<List<UserResponse>> users(HttpServletRequest request) {
        requireAdmin(request);
        return ResponseEntity.ok(repository.findAll().stream().map(IdentityConvert::user).toList());
    }
    @PostMapping("/users")
    public ResponseEntity<UserResponse> createUser(HttpServletRequest request, @RequestBody UserDraft draft) {
        requireAdmin(request);
        Account created = accounts.createAsAdmin(draft.getUsername(), draft.getPassword(), Role.USER, clock.instant());
        return ResponseEntity.status(201).body(IdentityConvert.user(created));
    }
    @PostMapping("/users/{id}/password/reset")
    public ResponseEntity<UserResponse> resetPassword(HttpServletRequest request, @PathVariable long id,
                                              @RequestBody PasswordDraft draft) {
        requireAdmin(request);
        return ResponseEntity.ok(IdentityConvert.user(accounts.resetPassword(id, draft.getPassword(), clock.instant())));
    }
    @PostMapping("/users/{id}/role")
    public ResponseEntity<UserResponse> changeRole(HttpServletRequest request, @PathVariable long id,
                                           @RequestBody RoleDraft draft) {
        Account actor = SessionInterceptor.currentAccount(request);
        return ResponseEntity.ok(IdentityConvert.user(accounts.changeRole(id, Role.valueOf(draft.getRole()), actor.getRole(), actor.getId())));
    }
    @PostMapping("/users/{id}/disable")
    public ResponseEntity<UserResponse> disable(HttpServletRequest request, @PathVariable long id) {
        requireAdmin(request);
        return ResponseEntity.ok(IdentityConvert.user(accounts.disable(id, clock.instant()).getAccount()));
    }
    @PostMapping("/users/{id}/enable")
    public ResponseEntity<UserResponse> enable(HttpServletRequest request, @PathVariable long id) {
        requireAdmin(request);
        return ResponseEntity.ok(IdentityConvert.user(accounts.enable(id, clock.instant()).getAccount()));
    }
    private void requireAdmin(HttpServletRequest request) {
        Role role = SessionInterceptor.currentAccount(request).getRole();
        if (role != Role.ADMIN && role != Role.SUPER_ADMIN) {
            throw new AuthorizationException(ErrorCode.FORBIDDEN, "需要管理员权限");
        }
    }
}
