package com.neocat.web

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import spock.lang.Shared
import spock.lang.Specification

/**
 * 接口文档契约：真实生成 {@code /v3/api-docs}，而不是只检查注解是否存在。
 *
 * <p>由 {@link OpenApiHarness} 在最小 Spring MVC 上下文里装载全部对外 Controller
 * （依赖用替身），让 SpringDoc 按注解生成 OpenAPI，再断言端点、tag、operationId、
 * 会话鉴权、匿名端点与 Protobuf 请求体等关键契约。
 * 不连接任何中间件，也不引入 Swagger UI。
 */
class OpenApiContractSpec extends Specification {

    @Shared
    JsonNode document = new ObjectMapper().readTree(OpenApiHarness.apiDocsJson())

    def "文档覆盖全部对外端点并带有 tag 与唯一 operationId"() {
        given:
        def paths = document.get('paths')
        def operations = []
        paths.fieldNames().each { path ->
            paths.get(path).fieldNames().each { method ->
                operations << [path: path, method: method, operation: paths.get(path).get(method)]
            }
        }

        expect: "每个端点都归属一个非空 tag，summary 与 operationId 都完整且唯一"
        !operations.isEmpty()
        operations.every { it.operation.get('tags').size() == 1 }
        operations.every { !it.operation.get('tags').get(0).asText().isBlank() }
        operations.every { it.operation.hasNonNull('operationId') }
        operations.every { !it.operation.get('summary').asText().isBlank() }
        operations.collect { it.operation.get('operationId').asText() }.toSet().size() == operations.size()

        and: "只文档化 /api 前缀下的对外端点"
        operations.every { it.path.startsWith('/api') }

        and: "受保护的读取端点确实出现在文档中"
        paths.has('/api/alerts')
        paths.has('/api/dashboards')
        paths.has('/api/reports/series')

        and: "Protobuf 端点仍声明二进制请求与响应"
        def ingest = paths.get('/api/v1/ingest').get('post')
        ingest.get('requestBody').get('content').has('application/x-protobuf')
        ingest.get('responses').get('202').get('content').has('application/x-protobuf')
    }

    def "鉴权按真实实现声明：全局会话 Cookie，三个匿名端点显式清空"() {
        expect:
        def scheme = document.get('components').get('securitySchemes').get('sessionCookie')
        scheme.get('type').asText() == 'apiKey'
        scheme.get('in').asText() == 'cookie'
        scheme.get('name').asText() == 'NC_SESSION'

        and: "全局安全要求只声明会话 Cookie 一种方案"
        document.get('security').size() == 1
        document.get('security').get(0).has('sessionCookie')

        and: "受保护端点继承全局安全要求，不另行覆盖"
        !document.get('paths').get('/api/alerts').get('get').has('security')
        !document.get('paths').get('/api/dashboards').get('get').has('security')

        and: "匿名端点显式清空安全要求"
        ['/api/login', '/api/platform/init-status', '/api/platform/initialize'].each { path ->
            def method = path == '/api/platform/init-status' ? 'get' : 'post'
            def security = document.get('paths').get(path).get(method).get('security')
            assert security.size() == 0: "$path 应为匿名端点，实际 $security"
        }
    }

    def "请求与响应 DTO 生成 schema，并保留精度与除零数组契约"() {
        expect:
        document.get('components').get('schemas').has('LoginRequest')
        document.get('components').get('schemas').has('AlertConditionDraft')
        document.get('components').get('schemas').has('CardSeriesResponse')

        and: "阈值精度契约写在字段说明里"
        def threshold = document.get('components').get('schemas').get('AlertConditionDraft')
                .get('properties').get('threshold')
        threshold.get('description').asText().contains('10^14')

        and: "卡片除零数组仍作为数组字段对外"
        document.get('components').get('schemas').get('CardSeriesResponse')
                .get('properties').get('isUndefined').get('type').asText() == 'array'
    }
}
