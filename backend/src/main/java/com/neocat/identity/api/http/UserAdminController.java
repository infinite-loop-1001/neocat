package com.neocat.identity.api.http;

import com.neocat.common.time.clock.TimeProvider;

import com.neocat.identity.api.http.dto.*;
import com.neocat.identity.api.http.convert.IdentityConvert;

import com.neocat.common.error.exception.AuthorizationException;
import com.neocat.common.error.ErrorCode;
import com.neocat.identity.domain.account.Account;
import com.neocat.identity.domain.account.AccountRepository;
import com.neocat.identity.domain.account.AccountService;
import com.neocat.identity.domain.account.Role;
import com.neocat.identity.api.http.auth.SessionInterceptor;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
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
import com.neocat.identity.api.http.dto.PasswordDraft;
import com.neocat.identity.api.http.dto.RoleDraft;
import com.neocat.identity.api.http.dto.UserDraft;
import com.neocat.identity.api.http.dto.UserResponse;

/** 账号管理 HTTP 入口。 */
@Tag(name = "账号管理", description = "管理员账号维护：创建、重置口令、角色变更、启停用")
@RestController
@RequestMapping("/api")
public class UserAdminController {
    private final AccountService accounts;

    private final AccountRepository repository;

    public UserAdminController(AccountService accounts, AccountRepository repository) {
        this.accounts = accounts;
        this.repository = repository;
    }
    @Operation(operationId = "listUsers", summary = "列出全部账号",
            description = "仅管理员可调用；返回角色、状态与是否需要改密。")
    @ApiResponse(responseCode = "200", description = "账号列表")
    @ApiResponse(responseCode = "403", description = "非管理员：FORBIDDEN")
    @GetMapping("/users")
    public ResponseEntity<List<UserResponse>> users(HttpServletRequest request) {
        requireAdmin(request);
        return ResponseEntity.ok(repository.findAll().stream().map(IdentityConvert::user).toList());
    }
    @Operation(operationId = "createUser", summary = "创建 USER 账号",
            description = "口令长度不足 8 位或用户名已存在时拒绝；管理员不能通过本端点创建管理员。")
    @ApiResponse(responseCode = "201", description = "已创建")
    @ApiResponse(responseCode = "409", description = "用户名已存在：USER_EXISTS")
    @PostMapping("/users")
    public ResponseEntity<UserResponse> createUser(HttpServletRequest request, @RequestBody UserDraft draft) {
        requireAdmin(request);
        Account created = accounts.createAsAdmin(draft.getUsername(), draft.getPassword(), Role.USER, TimeProvider.now());
        return ResponseEntity.status(201).body(IdentityConvert.user(created));
    }
    @Operation(operationId = "resetUserPassword", summary = "重置指定账号口令",
            description = "置强制改密并吊销该账号全部会话。")
    @ApiResponse(responseCode = "200", description = "已重置")
    @ApiResponse(responseCode = "404", description = "账号不存在：USER_NOT_FOUND")
    @PostMapping("/users/{id}/password/reset")
    public ResponseEntity<UserResponse> resetPassword(HttpServletRequest request, @PathVariable long id,
                                              @RequestBody PasswordDraft draft) {
        requireAdmin(request);
        return ResponseEntity.ok(IdentityConvert.user(accounts.resetPassword(id, draft.getPassword(), TimeProvider.now())));
    }
    @Operation(operationId = "changeUserRole", summary = "变更账号角色",
            description = "只允许 SUPER_ADMIN 调用；不能修改自己，管理员不能授予 ADMIN。")
    @ApiResponse(responseCode = "200", description = "已变更")
    @ApiResponse(responseCode = "403", description = "越权或修改自身：FORBIDDEN / CANNOT_MODIFY_SELF")
    @PostMapping("/users/{id}/role")
    public ResponseEntity<UserResponse> changeRole(HttpServletRequest request, @PathVariable long id,
                                           @RequestBody RoleDraft draft) {
        Account actor = SessionInterceptor.currentAccount(request);
        return ResponseEntity.ok(IdentityConvert.user(accounts.changeRole(id, Role.valueOf(draft.getRole()), actor.getRole(), actor.getId())));
    }
    @Operation(operationId = "disableUser", summary = "停用账号",
            description = "吊销会话并移除告警收件人；保留组织成员关系。")
    @ApiResponse(responseCode = "200", description = "已停用")
    @PostMapping("/users/{id}/disable")
    public ResponseEntity<UserResponse> disable(HttpServletRequest request, @PathVariable long id) {
        requireAdmin(request);
        return ResponseEntity.ok(IdentityConvert.user(accounts.disable(id, TimeProvider.now()).getAccount()));
    }
    @Operation(operationId = "enableUser", summary = "启用账号",
            description = "恢复登录与组织成员继承；不恢复此前被移除的告警收件人。")
    @ApiResponse(responseCode = "200", description = "已启用")
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
