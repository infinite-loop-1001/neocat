package com.neocat.identity.api.http;

import com.neocat.common.time.clock.TimeProvider;

import com.neocat.identity.api.http.dto.IdentityDtos.*;
import com.neocat.identity.api.http.convert.IdentityConvert;

import com.neocat.identity.domain.account.Account;
import com.neocat.identity.domain.account.AccountService;
import com.neocat.identity.domain.auth.AuthenticationService;
import com.neocat.identity.domain.auth.LoginResult;
import com.neocat.identity.domain.auth.LoginTarget;
import com.neocat.identity.domain.auth.ServiceAvailability;
import com.neocat.identity.domain.session.Session;
import com.neocat.identity.api.http.auth.SessionInterceptor;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirements;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 身份与会话接口（技术方案 03-api-contract.md §2）。
 *
 * <p>关键语义：
 * <ul>
 *   <li>登录失败统一返回 {@code BAD_CREDENTIALS}，**不区分账号不存在与口令错误**（PRD 01 §4.1）；</li>
 *   <li>会话为 HttpOnly Cookie，滑动 30 分钟；</li>
 *   <li>首登/重置后返回 {@code mustChangePassword}，前端据此进入改密流程（PRD 01 §4.3）；</li>
 *   <li>登录成功后按「最近访问服务是否有数据」决定落点（PRD 01 §4.1）。</li>
 * </ul>
 */
@Tag(name = "身份与会话", description = "登录、登出、当前用户与改密")
@RestController
@RequestMapping("/api")
public class IdentityController {

    private final AuthenticationService authentication;

    private final AccountService accounts;

    private final ServiceAvailability availability;


    public IdentityController(AuthenticationService authentication, AccountService accounts,
                              ServiceAvailability availability) {
        this.authentication = authentication;
        this.accounts = accounts;
        this.availability = availability;
    }
    @Operation(operationId = "login", summary = "登录并下发会话 Cookie",
            description = "登录失败统一返回 BAD_CREDENTIALS，不区分账号不存在与口令错误；"
                    + "成功后按最近访问服务是否有数据决定落点。")
    @ApiResponse(responseCode = "200", description = "登录成功，响应体包含落点 entry")
    @ApiResponse(responseCode = "401", description = "账号或口令错误：BAD_CREDENTIALS")
    @SecurityRequirements
    @PostMapping("/login")
    public ResponseEntity<LoginResponse> login(@RequestBody LoginRequest request,
                                                     HttpServletResponse response) {
        LoginResult result = authentication.login(request.getUsername(), request.getPassword(), TimeProvider.now());

        Cookie cookie = new Cookie(SessionInterceptor.SESSION_COOKIE, result.getSession().getId());
        cookie.setHttpOnly(true);
        cookie.setPath("/");
        cookie.setMaxAge((int) Session.SLIDING_SECONDS);
        response.addCookie(cookie);

        LoginTarget target = accounts.resolveLoginTarget(result.getAccount().getId(), availability);

        return ResponseEntity.ok(IdentityConvert.login(result, target));
    }
    /** 登出只注销当前会话（PRD 01 §4.2）。 */
    @Operation(operationId = "logout", summary = "注销当前会话",
            description = "只注销当前会话并清除会话 Cookie，不影响该账号的其他会话。")
    @ApiResponse(responseCode = "200", description = "已注销：{ ok: true }")
    @PostMapping("/logout")
    public ResponseEntity<Success> logout(HttpServletResponse response) {
        Cookie cookie = new Cookie(SessionInterceptor.SESSION_COOKIE, "");
        cookie.setHttpOnly(true);
        cookie.setPath("/");
        cookie.setMaxAge(0);
        response.addCookie(cookie);
        return ResponseEntity.ok(new Success(true));
    }
    @Operation(operationId = "currentUser", summary = "查询当前登录账号",
            description = "必须携带有效会话 Cookie；返回 mustChangePassword 供前端决定是否进入改密流程。")
    @ApiResponse(responseCode = "200", description = "当前账号信息")
    @ApiResponse(responseCode = "401", description = "无有效会话：UNAUTHENTICATED")
    @GetMapping("/me")
    public ResponseEntity<CurrentUser> me(HttpServletRequest request) {
        Account account = SessionInterceptor.currentAccount(request);
        return ResponseEntity.ok(IdentityConvert.current(account));
    }
    /** 首次改密 / 重置后改密（PRD 01 §4.3）。 */
    @Operation(operationId = "changePassword", summary = "修改当前账号口令",
            description = "强制改密会话只允许本端点、登出与查询自身；新口令不满足规则返回 400。")
    @ApiResponse(responseCode = "200", description = "已修改：{ ok: true }")
    @ApiResponse(responseCode = "400", description = "口令不合法：PASSWORD_TOO_SHORT 等")
    @PostMapping("/me/password")
    public ResponseEntity<Success> changePassword(@RequestBody ChangePasswordRequest body,
                                                              HttpServletRequest request) {
        Account account = SessionInterceptor.currentAccount(request);
        accounts.changePassword(account.getId(), body.getOldPassword(), body.getNewPassword(), TimeProvider.now());
        return ResponseEntity.ok(new Success(true));
    }
}
