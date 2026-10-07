package com.neocat.identity.api.http;

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
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Clock;
import java.util.LinkedHashMap;
import java.util.Map;

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
@RestController
@RequestMapping("/api")
public class IdentityController {

    private final AuthenticationService authentication;

    private final AccountService accounts;

    private final ServiceAvailability availability;

    private final Clock clock;

    public IdentityController(AuthenticationService authentication, AccountService accounts,
                              ServiceAvailability availability, Clock clock) {
        this.authentication = authentication;
        this.accounts = accounts;
        this.availability = availability;
        this.clock = clock;
    }
    @PostMapping("/login")
    public ResponseEntity<LoginResponse> login(@RequestBody LoginRequest request,
                                                     HttpServletResponse response) {
        LoginResult result = authentication.login(request.getUsername(), request.getPassword(), clock.instant());

        Cookie cookie = new Cookie(SessionInterceptor.SESSION_COOKIE, result.getSession().getId());
        cookie.setHttpOnly(true);
        cookie.setPath("/");
        cookie.setMaxAge((int) Session.SLIDING_SECONDS);
        response.addCookie(cookie);

        LoginTarget target = accounts.resolveLoginTarget(result.getAccount().getId(), availability);

        return ResponseEntity.ok(IdentityConvert.login(result, target));
    }
    /** 登出只注销当前会话（PRD 01 §4.2）。 */
    @PostMapping("/logout")
    public ResponseEntity<Success> logout(HttpServletResponse response) {
        Cookie cookie = new Cookie(SessionInterceptor.SESSION_COOKIE, "");
        cookie.setHttpOnly(true);
        cookie.setPath("/");
        cookie.setMaxAge(0);
        response.addCookie(cookie);
        return ResponseEntity.ok(new Success(true));
    }
    @GetMapping("/me")
    public ResponseEntity<CurrentUser> me(HttpServletRequest request) {
        Account account = SessionInterceptor.currentAccount(request);
        return ResponseEntity.ok(IdentityConvert.current(account));
    }
    /** 首次改密 / 重置后改密（PRD 01 §4.3）。 */
    @PostMapping("/me/password")
    public ResponseEntity<Success> changePassword(@RequestBody ChangePasswordRequest body,
                                                              HttpServletRequest request) {
        Account account = SessionInterceptor.currentAccount(request);
        accounts.changePassword(account.getId(), body.getOldPassword(), body.getNewPassword(), clock.instant());
        return ResponseEntity.ok(new Success(true));
    }
}

