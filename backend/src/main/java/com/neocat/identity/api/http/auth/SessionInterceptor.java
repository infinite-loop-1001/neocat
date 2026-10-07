package com.neocat.identity.api.http.auth;

import com.neocat.common.error.ErrorCode;
import com.neocat.common.error.exception.AuthenticationException;
import com.neocat.common.error.exception.AuthorizationException;
import com.neocat.common.http.context.RequestActor;
import com.neocat.identity.domain.account.Account;
import com.neocat.identity.domain.auth.SessionGuard;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

import java.time.Clock;
import java.util.Arrays;
import java.util.Optional;
import java.util.Objects;

/**
 * 会话拦截器（技术方案 03-api-contract.md §1.2、§2）。
 *
 * <p>职责：
 * <ol>
 *   <li>白名单端点（平台初始化、登录）直接放行；</li>
 *   <li>其余端点必须携带有效会话，会话续期由 {@link SessionGuard} 完成；</li>
 *   <li>强制改密会话只允许改密、登出、查询自身，其余请求返回 403；</li>
 *   <li>把当前账号放入请求属性，避免控制器各自解析会话。</li>
 * </ol>
 *
 * <p>端点标识统一为 {@code "METHOD /path"}，与 {@link AuthWhitelist} 保持精确匹配。
 */
@Component
public class SessionInterceptor implements HandlerInterceptor {

    public static final String ACCOUNT_ATTRIBUTE = "neocat.account";

    public static final String SESSION_COOKIE = "NC_SESSION";

    private final SessionGuard guard;

    private final Clock clock;

    public SessionInterceptor(SessionGuard guard, Clock clock) {
        this.guard = guard;
        this.clock = clock;
    }
    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        String endpoint = endpointOf(request);
        if (AuthWhitelist.anonymousAllowed(endpoint)) {
            return true;
        }

        String sessionId = sessionIdOf(request);
        if (Objects.isNull(sessionId)) {
            throw new AuthenticationException(ErrorCode.UNAUTHENTICATED);
        }

        Account account = guard.requireSession(sessionId, clock.instant());
        if (account.isMustChangePassword() && !AuthWhitelist.allowedDuringPasswordChange(endpoint)) {
            throw new AuthorizationException(ErrorCode.PASSWORD_CHANGE_REQUIRED);
        }

        request.setAttribute(ACCOUNT_ATTRIBUTE, account);
        request.setAttribute(RequestActor.ATTRIBUTE, new RequestActor(account.getId(), account.getRole().name()));
        return true;
    }
    /** 读取当前账号；拦截器已保证存在。 */
    public static Account currentAccount(HttpServletRequest request) {
        Object value = request.getAttribute(ACCOUNT_ATTRIBUTE);
        if (value instanceof Account account) {
            return account;
        }
        throw new AuthenticationException(ErrorCode.UNAUTHENTICATED);
    }
    /** 由请求推导端点标识，供白名单精确匹配。 */
    public static String endpointOf(HttpServletRequest request) {
        return request.getMethod() + " " + normalize(request.getRequestURI());
    }
    @org.springframework.lang.Nullable
    private String sessionIdOf(HttpServletRequest request) {
        Cookie[] cookies = request.getCookies();
        if (Objects.isNull(cookies)) {
            return null;
        }
        return Arrays.stream(cookies)
                .filter(cookie -> SESSION_COOKIE.equals(cookie.getName()))
                .map(Cookie::getValue)
                .filter(value -> Objects.nonNull(value) && !value.isBlank())
                .findFirst().orElse(null);
    }
    /** 去掉结尾斜杠，使端点标识可精确匹配。 */
    private static String normalize(String uri) {
        String path = Objects.isNull(uri) ? "/" : uri;
        int query = path.indexOf('?');
        if (query >= 0) {
            path = path.substring(0, query);
        }
        while (path.length() > 1 && path.endsWith("/")) {
            path = path.substring(0, path.length() - 1);
        }
        return path;
    }
}
