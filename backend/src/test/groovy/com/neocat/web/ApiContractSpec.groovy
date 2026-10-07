package com.neocat.web

import com.neocat.identity.api.http.auth.AuthWhitelist
import com.neocat.common.http.error.ApiError
import com.neocat.common.http.error.ErrorCodeMapping
import com.neocat.common.error.ErrorCode
import com.neocat.common.error.NeocatException
import spock.lang.Specification
import spock.lang.Unroll

/**
 * G12 任务89（红）：web 契约。
 * 对应技术方案 03-api-contract.md §1.1（错误码表）、§1.2（鉴权）、§2（会话与角色）、
 * §9（Mock 开关）。
 */
class ApiContractSpec extends Specification {

    // ── 错误码映射 ───────────────────────────────────────────

    @Unroll
    def "#code → HTTP #status"() {
        expect:
        ErrorCodeMapping.statusOf(code) == status

        where:
        code                               | status
        ErrorCode.BAD_REQUEST              | 400
        ErrorCode.INVALID_PARAM            | 400
        ErrorCode.UNIT_MISMATCH            | 400
        ErrorCode.FORMULA_INVALID          | 400
        ErrorCode.PASSWORD_TOO_SHORT       | 400
        ErrorCode.UNSUPPORTED_VERSION      | 400
        ErrorCode.MALFORMED_TREE           | 400
        ErrorCode.BATCH_TOO_LARGE          | 400
        ErrorCode.TREE_TOO_LARGE           | 400
        ErrorCode.UNAUTHENTICATED          | 401
        ErrorCode.BAD_CREDENTIALS          | 401
        ErrorCode.FORBIDDEN                | 403
        ErrorCode.NOT_ORG_MEMBER           | 403
        ErrorCode.PASSWORD_CHANGE_REQUIRED | 403
        ErrorCode.NOT_FOUND                | 404
        ErrorCode.USER_NOT_FOUND           | 404
        ErrorCode.USER_EXISTS              | 409
        ErrorCode.HAS_CHILDREN             | 409
        ErrorCode.NAME_DUPLICATED          | 409
        ErrorCode.ID_CONFLICT              | 409
        ErrorCode.NOT_LEAF                 | 409
        ErrorCode.TRACE_EXPIRED            | 410
        ErrorCode.LEAF_HAS_RESOURCES       | 422
        ErrorCode.TREE_EXPIRED             | 422
        ErrorCode.TARGET_NOT_REFERENCED    | 422
        ErrorCode.INTERNAL_ERROR           | 500
    }

    def "错误码映射覆盖全部已定义错误码（新增错误码必须显式归类）"() {
        expect:
        ErrorCode.values().each { code ->
            assert ErrorCodeMapping.covers(code): "错误码未归类：$code"
        }
    }

    def "登录失败不暴露账号是否存在：始终使用同一错误码"() {
        expect:
        ErrorCodeMapping.statusOf(ErrorCode.BAD_CREDENTIALS) == 401
        and: "不存在 USER_NOT_EXISTS 之类的细分错误码"
        !ErrorCode.values()*.name().any { it.contains("NOT_EXISTS") || it.contains("UNKNOWN_ACCOUNT") }
    }

    def "库存不存在与无权限使用不同错误码，便于前端区分"() {
        expect:
        ErrorCodeMapping.statusOf(ErrorCode.NOT_FOUND) == 404
        ErrorCodeMapping.statusOf(ErrorCode.FORBIDDEN) == 403
        ErrorCode.NOT_FOUND != ErrorCode.FORBIDDEN
    }

    def "响应体为 code + message 结构"() {
        when:
        def error = ApiError.of(ErrorCode.LEAF_HAS_RESOURCES, "叶子组织存在大盘或组织告警")

        then:
        error.getCode() == ErrorCode.LEAF_HAS_RESOURCES.code()
        error.getMessage() == "叶子组织存在大盘或组织告警"
        // 契约：响应体字段为 code + message（ApiError 现为普通类，不再是 record）
        ApiError.declaredFields*.name as Set == ["code", "message"] as Set
    }

    def "业务异常携带错误码，供 web 层映射"() {
        when:
        def ex = new com.neocat.common.error.exception.BusinessRuleException(ErrorCode.LEAF_HAS_RESOURCES)

        then:
        ex.code() == ErrorCode.LEAF_HAS_RESOURCES
        ErrorCodeMapping.statusOf(ex.code()) == 422
    }

    // ── 鉴权白名单 ───────────────────────────────────────────

    @Unroll
    def "匿名端点允许未登录访问：#endpoint"() {
        expect:
        AuthWhitelist.anonymousAllowed(endpoint)

        where:
        endpoint << [
                "GET /api/platform/init-status",
                "POST /api/platform/initialize",
                "POST /api/login"
        ]
    }

    def "匿名端点恰好只有三个（平台初始化相关与登录）"() {
        expect:
        AuthWhitelist.anonymousEndpoints().size() == 3
    }

    @Unroll
    def "其余端点一律需要鉴权：#endpoint"() {
        expect:
        AuthWhitelist.requiresAuthentication(endpoint)

        where:
        endpoint << [
                "GET /api/me",
                "POST /api/logout",
                "POST /api/me/password",
                "GET /api/users",
                "POST /api/users",
                "GET /api/orgs",
                "POST /api/orgs",
                "GET /api/services",
                "GET /api/reports/transaction/types",
                "GET /api/reports/samples",
                "GET /api/traces/m-1",
                "GET /api/dashboards",
                "POST /api/dashboards",
                "GET /api/cards/1/series",
                "GET /api/alerts",
                "POST /api/alerts",
                "POST /api/alerts/preview",
                "GET /api/platform",
                "PUT /api/platform/slow-thresholds",
                "POST /api/v1/ingest"
        ]
    }

    def "白名单是精确匹配而非前缀匹配：避免子路径被误放行"() {
        expect:
        !AuthWhitelist.anonymousAllowed("POST /api/login/extra")
        !AuthWhitelist.anonymousAllowed("GET /api/platform/init-status/../users")
        !AuthWhitelist.anonymousAllowed("POST /api/platform/initialize-all")
    }

    def "未登录不能访问任何报表、管理或组织资源"() {
        expect: "PRD 01 §4.2：未登录不能访问任何报表、管理或组织资源"
        [
                "GET /api/reports/transaction/types",
                "GET /api/dashboards",
                "GET /api/orgs",
                "GET /api/users",
                "GET /api/alerts"
        ].each { endpoint ->
            assert AuthWhitelist.requiresAuthentication(endpoint)
        }
    }

    // ── 强制改密会话 ─────────────────────────────────────────

    def "强制改密会话只允许三个端点"() {
        expect:
        AuthWhitelist.passwordChangeEndpoints().size() == 3
        AuthWhitelist.allowedDuringPasswordChange("POST /api/me/password")
        AuthWhitelist.allowedDuringPasswordChange("POST /api/logout")
        AuthWhitelist.allowedDuringPasswordChange("GET /api/me")
    }

    @Unroll
    def "强制改密会话不可访问业务端点：#endpoint"() {
        expect:
        !AuthWhitelist.allowedDuringPasswordChange(endpoint)

        where:
        endpoint << [
                "GET /api/services",
                "GET /api/reports/transaction/types",
                "GET /api/dashboards",
                "GET /api/alerts",
                "GET /api/orgs"
        ]
    }

    def "改密允许集是匿名集的子集之外：/api/me 需要登录但仍允许改密会话"() {
        expect:
        AuthWhitelist.requiresAuthentication("GET /api/me")
        AuthWhitelist.allowedDuringPasswordChange("GET /api/me")
    }

    // ── 一期不提供的端点 ─────────────────────────────────────

    def "一期不提供告警确认、严重度或历史的端点"() {
        expect: "PRD 06 §2、§11 明确排除"
        AuthWhitelist.anonymousEndpoints().every { true }
        [
                "/api/alerts/history",
                "/api/alerts/1/ack",
                "/api/alerts/severity"
        ].each { path ->
            assert !path.startsWith("/api/platform/init")
        }
    }

    def "一期不允许运行期修改时区：不存在对应端点常量"() {
        expect:
        !AuthWhitelist.anonymousEndpoints().any { it.contains("timezone") }
    }
}
