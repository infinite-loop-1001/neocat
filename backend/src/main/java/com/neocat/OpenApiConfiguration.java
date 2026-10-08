package com.neocat;

import io.swagger.v3.oas.annotations.OpenAPIDefinition;
import io.swagger.v3.oas.annotations.enums.SecuritySchemeIn;
import io.swagger.v3.oas.annotations.enums.SecuritySchemeType;
import io.swagger.v3.oas.annotations.info.Info;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.security.SecurityScheme;
import org.springframework.context.annotation.Configuration;

/**
 * 接口文档定义（技术方案 03-api-contract.md §1；编码规范 http-api.md）。
 *
 * <p>只生成机器可读的 OpenAPI 文档，不引入 Swagger UI 页面：
 * 运行时入口是 {@code GET /v3/api-docs}，默认关闭，由部署按需开启。
 *
 * <p>鉴权按真实实现声明：除 {@code /api/platform/init-status}、{@code /api/platform/initialize}
 * 与 {@code /api/login} 外，所有端点都要求有效会话 Cookie。全局安全要求在此声明一次，
 * 匿名端点用 {@code @SecurityRequirements} 显式清空，避免逐个端点重复且易于漂移。
 */
@Configuration(proxyBeanMethods = false)
@OpenAPIDefinition(
        info = @Info(
                title = "NeoCat 监控平台 API",
                version = "1.0",
                description = "上报接收、分析报表、Trace、大盘与告警的对外 HTTP 契约；"
                        + "文档只描述现有行为，不代表运行期校验。"),
        security = @SecurityRequirement(name = OpenApiConfiguration.SESSION_SCHEME))
@SecurityScheme(
        name = OpenApiConfiguration.SESSION_SCHEME,
        type = SecuritySchemeType.APIKEY,
        in = SecuritySchemeIn.COOKIE,
        paramName = "NC_SESSION",
        description = "登录成功后下发的 HttpOnly 会话 Cookie；缺失或失效返回 401。")
public class OpenApiConfiguration {

    /** 全局安全方案名，与 {@code SessionInterceptor.SESSION_COOKIE} 的 Cookie 名对应。 */
    public static final String SESSION_SCHEME = "sessionCookie";

    /** 公开文档路径前缀，用于部署侧访问控制与回归断言。 */
    public static final String API_DOCS_PATH = "/v3/api-docs";
}
