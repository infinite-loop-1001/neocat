package com.neocat.identity.api.http;

import com.neocat.common.time.clock.TimeProvider;

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
import java.util.Objects;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** 账号管理 HTTP 入口。 */
@RestController
@RequestMapping("/api")
public class UserAdminController {
    private final AccountService accounts;

    private final AccountRepository repository;

    public UserAdminController(AccountService accounts, AccountRepository repository) {
        this.accounts = accounts;
        this.repository = repository;
    }
    @GetMapping("/users")
    public ResponseEntity<List<UserResponse>> users(HttpServletRequest request) {
        requireAdmin(request);
        return ResponseEntity.ok(repository.findAll().stream().map(IdentityConvert::user).toList());
    }
    @PostMapping("/users")
    public ResponseEntity<UserResponse> createUser(HttpServletRequest request, @RequestBody UserDraft draft) {
        requireAdmin(request);
        Account created = accounts.createAsAdmin(draft.getUsername(), draft.getPassword(), Role.USER, TimeProvider.now());
        return ResponseEntity.status(201).body(IdentityConvert.user(created));
    }
    @PostMapping("/users/{id}/password/reset")
    public ResponseEntity<UserResponse> resetPassword(HttpServletRequest request, @PathVariable long id,
                                              @RequestBody PasswordDraft draft) {
        requireAdmin(request);
        return ResponseEntity.ok(IdentityConvert.user(accounts.resetPassword(id, draft.getPassword(), TimeProvider.now())));
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
        return ResponseEntity.ok(IdentityConvert.user(accounts.disable(id, TimeProvider.now()).getAccount()));
    }
    @PostMapping("/users/{id}/enable")
    public ResponseEntity<UserResponse> enable(HttpServletRequest request, @PathVariable long id) {
        requireAdmin(request);
        return ResponseEntity.ok(IdentityConvert.user(accounts.enable(id, TimeProvider.now()).getAccount()));
    }
    private void requireAdmin(HttpServletRequest request) {
        Role role = SessionInterceptor.currentAccount(request).getRole();
        if (!Objects.equals(role, Role.ADMIN) && !Objects.equals(role, Role.SUPER_ADMIN)) {
            throw new AuthorizationException(ErrorCode.FORBIDDEN, "需要管理员权限");
        }
    }
}
