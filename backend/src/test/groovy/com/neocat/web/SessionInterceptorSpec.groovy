package com.neocat.web

import com.neocat.identity.api.http.auth.SessionInterceptor
import com.neocat.identity.api.http.auth.AuthWhitelist
import com.neocat.common.http.error.ApiExceptionHandler
import com.neocat.common.http.error.ErrorCodeMapping
import com.neocat.common.error.ErrorCode
import com.neocat.common.error.NeocatException
import com.neocat.identity.domain.account.Account
import com.neocat.identity.domain.account.AccountRepository
import com.neocat.identity.domain.account.AccountStatus
import com.neocat.identity.domain.account.Role
import com.neocat.identity.domain.auth.SessionGuard
import com.neocat.identity.domain.session.SessionRepository
import com.neocat.identity.domain.session.Session
import jakarta.servlet.http.Cookie
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import spock.lang.Specification
import spock.lang.Unroll

import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import com.neocat.common.error.exception.AuthenticationException
import com.neocat.common.error.exception.AuthorizationException
import com.neocat.common.error.exception.BusinessRuleException
import com.neocat.common.error.exception.ConflictException
import com.neocat.common.error.exception.ExpiredException
import com.neocat.common.error.exception.ResourceNotFoundException
import com.neocat.common.error.exception.ValidationException
import com.neocat.common.time.clock.TimeProvider

/**
 * G12 任务89（红）：会话拦截器与异常映射。
 * 对应技术方案 03-api-contract.md §1.1（错误码）、§1.2（鉴权）、§2（会话与角色）。
 *
 * <p>直接用替身驱动拦截器，不需要启动 Spring 上下文，也不需要任何中间件。
 */
class SessionInterceptorSpec extends Specification {
    def cleanup() {
        TimeProvider.clock = Clock.systemUTC()
    }

    static final Instant NOW = Instant.parse("2026-09-24T04:00:00Z")

    AccountRepository accounts
    SessionRepository sessions
    SessionInterceptor interceptor
    HttpServletRequest request
    HttpServletResponse response

    def setup() {
        accounts = Stub(AccountRepository)
        sessions = Stub(SessionRepository)
        TimeProvider.clock = Clock.fixed(NOW, ZoneOffset.UTC)
        interceptor = new SessionInterceptor(new SessionGuard(accounts, sessions))
        request = Mock()
        response = Mock()
    }

    def account(String username, boolean mustChangePassword = false) {
        return new Account(7L, username, 'h', Role.USER, AccountStatus.ENABLED, mustChangePassword, NOW)
    }

    def stubSession(Account user, Instant expiresAt = NOW.plusSeconds(1800)) {
        def session = new Session('session-7', user.getId(), expiresAt)
        sessions = Stub(SessionRepository) {
            isValid('session-7', _) >> { String id, Instant time -> time.isBefore(expiresAt) }
            findSession('session-7') >> session
        }
        accounts = Stub(AccountRepository) { findById(user.getId()) >> user }
        TimeProvider.clock = Clock.fixed(NOW, ZoneOffset.UTC)
        interceptor = new SessionInterceptor(new SessionGuard(accounts, sessions))
        return session
    }

    def stubRequest(String method, String uri, String sessionId = null) {
        request.getMethod() >> method
        request.getRequestURI() >> uri
        request.getCookies() >> (sessionId == null
                ? null
                : [new Cookie(SessionInterceptor.SESSION_COOKIE, sessionId)] as Cookie[])
    }

    // ── 白名单放行 ───────────────────────────────────────────

    @Unroll
    def "白名单端点无需会话即可访问：#method #uri"() {
        given:
        stubRequest(method, uri)

        expect:
        interceptor.preHandle(request, response, new Object())

        where:
        method | uri
        "GET"  | "/api/platform/init-status"
        "POST" | "/api/platform/initialize"
        "POST" | "/api/login"
    }

    def "白名单端点即使携带无效会话也不报错"() {
        given:
        stubRequest("POST", "/api/login", "ghost-session")

        expect:
        interceptor.preHandle(request, response, new Object())
    }

    // ── 非白名单端点必须登录 ─────────────────────────────────

    @Unroll
    def "未登录访问 #uri 被拒绝"() {
        given:
        stubRequest(method, uri)

        when:
        interceptor.preHandle(request, response, new Object())

        then:
        def e = thrown(NeocatException)
        e.code() == ErrorCode.UNAUTHENTICATED

        where:
        method | uri
        "GET"  | "/api/me"
        "GET"  | "/api/services"
        "GET"  | "/api/reports/transaction/types"
        "GET"  | "/api/dashboards"
        "GET"  | "/api/alerts"
        "GET"  | "/api/orgs"
        "GET"  | "/api/users"
        "POST" | "/api/v1/ingest"
    }

    def "携带无效会话被拒绝"() {
        given:
        stubRequest("GET", "/api/services", "not-a-session")

        when:
        interceptor.preHandle(request, response, new Object())

        then:
        def e = thrown(NeocatException)
        e.code() == ErrorCode.UNAUTHENTICATED
    }

    def "有效会话放行并绑定当前账号"() {
        given:
        def alice = account("alice")
        def session = stubSession(alice)
        stubRequest("GET", "/api/services", session.getId())

        when:
        def allowed = interceptor.preHandle(request, response, new Object())

        then: "放行且账号被放入请求属性"
        allowed
        1 * request.setAttribute(SessionInterceptor.ACCOUNT_ATTRIBUTE, { it instanceof Account && it.getUsername() == "alice" })
    }

    def "会话过期后访问被拒绝"() {
        given:
        def alice = account("alice")
        def session = stubSession(alice)
        stubRequest("GET", "/api/services", session.getId())
        TimeProvider.clock = Clock.fixed(NOW.plusSeconds(31 * 60), ZoneOffset.UTC)
        def later = new SessionInterceptor(new SessionGuard(accounts, sessions))

        when:
        later.preHandle(request, response, new Object())

        then:
        def e = thrown(NeocatException)
        e.code() == ErrorCode.UNAUTHENTICATED
    }

    def "账号被禁用后其会话立即失效"() {
        given:
        def alice = account("alice")
        def session = stubSession(alice.withStatus(AccountStatus.DISABLED))
        stubRequest("GET", "/api/services", session.getId())

        when:
        interceptor.preHandle(request, response, new Object())

        then:
        def e = thrown(NeocatException)
        e.code() == ErrorCode.UNAUTHENTICATED
    }

    // ── 强制改密会话限制 ─────────────────────────────────────

    @Unroll
    def "强制改密会话可访问 #uri"() {
        given:
        def alice = account("alice", true)
        def session = stubSession(alice)
        stubRequest(method, uri, session.getId())

        expect:
        interceptor.preHandle(request, response, new Object())

        where:
        method | uri
        "POST" | "/api/me/password"
        "POST" | "/api/logout"
        "GET"  | "/api/me"
    }

    @Unroll
    def "强制改密会话不可访问业务端点：#uri"() {
        given:
        def alice = account("alice", true)
        def session = stubSession(alice)
        stubRequest(method, uri, session.getId())

        when:
        interceptor.preHandle(request, response, new Object())

        then:
        def e = thrown(NeocatException)
        e.code() == ErrorCode.PASSWORD_CHANGE_REQUIRED

        where:
        method | uri
        "GET"  | "/api/services"
        "GET"  | "/api/reports/transaction/types"
        "GET"  | "/api/dashboards"
        "GET"  | "/api/alerts"
        "GET"  | "/api/orgs"
        "GET"  | "/api/users"
    }

    def "改密完成后限制解除"() {
        given:
        def alice = account("alice", true)
        def session = stubSession(alice)
        stubRequest("GET", "/api/services", session.getId())

        when: "清除强制改密标记"
        stubSession(alice.withPassword('h', false))

        then:
        interceptor.preHandle(request, response, new Object())
    }

    // ── 端点标识规范化 ───────────────────────────────────────

    def "端点标识为 METHOD + 路径"() {
        given:
        stubRequest("POST", "/api/login")

        expect:
        SessionInterceptor.endpointOf(request) == "POST /api/login"
    }

    def "结尾斜杠被规范化，避免绕过白名单判定"() {
        given:
        stubRequest("POST", "/api/login/")

        expect:
        SessionInterceptor.endpointOf(request) == "POST /api/login"
        AuthWhitelist.anonymousAllowed(SessionInterceptor.endpointOf(request))
    }

    def "相似路径不被误判为白名单"() {
        given:
        stubRequest("POST", "/api/login/extra")

        expect:
        !AuthWhitelist.anonymousAllowed(SessionInterceptor.endpointOf(request))
    }

    // ── 异常映射 ─────────────────────────────────────────────

    @Unroll
    def "业务异常 #error.code() 映射为 HTTP #status 与稳定 code"() {
        given:
        def handler = new ApiExceptionHandler()

        when:
        def body = handler.handleBusiness(error).body

        then:
        body.getCode() == error.code().code()
        body.getMessage() == error.message
        ErrorCodeMapping.statusOf(error.code()) == status

        where:
        error                                                     | status
        new BusinessRuleException(ErrorCode.LEAF_HAS_RESOURCES)         | 422
        new AuthorizationException(ErrorCode.NOT_ORG_MEMBER)            | 403
        new ExpiredException(ErrorCode.TRACE_EXPIRED)                   | 410
        new ValidationException(ErrorCode.UNIT_MISMATCH)                | 400
        new ConflictException(ErrorCode.ID_CONFLICT)                    | 409
        new ConflictException(ErrorCode.USER_EXISTS, 'alice')           | 409
        new ConflictException(ErrorCode.HAS_CHILDREN)                   | 409
        new ResourceNotFoundException(ErrorCode.NOT_FOUND, '资源')       | 404
        new AuthenticationException(ErrorCode.UNAUTHENTICATED)          | 401
    }

    def "参数错误映射为 400 且带 INVALID_PARAM"() {
        given:
        def handler = new ApiExceptionHandler()

        when:
        def body = handler.handleBadRequest(new IllegalArgumentException("参数不合法")).body

        then:
        body.getCode() == ErrorCode.INVALID_PARAM.code()
    }

    def "资源不存在映射为 404"() {
        given:
        def handler = new ApiExceptionHandler()

        when:
        def body = handler.handleNotFound(new NoSuchElementException("规则不存在")).body

        then:
        body.getCode() == ErrorCode.NOT_FOUND.code()
    }

    def "未预期异常映射为 500 且不泄露内部细节"() {
        given:
        def handler = new ApiExceptionHandler()

        when:
        def body = handler.handleUnexpected(new RuntimeException("数据库连接串泄露风险")).body

        then:
        body.getCode() == ErrorCode.INTERNAL_ERROR.code()
        body.getMessage() == "服务内部错误"
        !body.getMessage().contains("数据库")
    }
}
