package com.neocat.identity.api.http.auth;

import java.util.Set;

/**
 * 鉴权白名单（技术方案 03-api-contract.md §2、§1.2）。
 *
 * <p>只有平台初始化与登录相关端点允许匿名访问；
 * **其余一切端点（报表、管理、组织、大盘、告警）都必须携带有效会话**。
 * 白名单是显式清单而非前缀匹配，避免「新增端点时忘记加鉴权」。
 */
public class AuthWhitelist {

    /** 端点标识格式："METHOD /path"。 */
    private static final Set<String> ANONYMOUS = Set.of(
            "GET /api/platform/init-status",
            "POST /api/platform/initialize",
            "POST /api/login");

    /** 强制改密会话仍可访问的端点。 */
    private static final Set<String> PASSWORD_CHANGE_ALLOWED = Set.of(
            "POST /api/me/password",
            "POST /api/logout",
            "GET /api/me");

    private AuthWhitelist() {
    }
    public static boolean anonymousAllowed(String endpoint) {
        return ANONYMOUS.contains(endpoint);
    }
    public static boolean requiresAuthentication(String endpoint) {
        return !anonymousAllowed(endpoint);
    }
    /** 强制改密会话是否可访问该端点。 */
    public static boolean allowedDuringPasswordChange(String endpoint) {
        return PASSWORD_CHANGE_ALLOWED.contains(endpoint);
    }
    public static Set<String> anonymousEndpoints() {
        return ANONYMOUS;
    }
    public static Set<String> passwordChangeEndpoints() {
        return PASSWORD_CHANGE_ALLOWED;
    }
}
