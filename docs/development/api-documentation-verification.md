# 接口文档（SpringDoc + OpenAPI 3）落地与离线验证

日期：2026-10-09。范围为后端全部对外 HTTP 接口的文档注解与生成物，**不引入 Swagger UI**。

## 约束与实现

- 约束写入 `.agents/coding-standards/http-api.md`（新增「接口文档（强制）」章节）与 `.agents/coding-standards/README.md` 强制项 13。
- 依赖只加 `org.springdoc:springdoc-openapi-starter-webmvc-api:2.6.0`（Spring Boot 3.3.x 对应 2.6.x），不含 `…-ui`、Swagger UI WebJar 或 Springfox；解析出的 Swagger 传递依赖只有 `swagger-core-jakarta` / `swagger-annotations-jakarta` / `swagger-models-jakarta`。
- 文档入口 `GET /v3/api-docs`（JSON）与 `/v3/api-docs.yaml`，由 `backend/src/main/resources/application.properties` 默认关闭（`springdoc.api-docs.enabled=false`），仅文档化 `springdoc.paths-to-match=/api/**`。

| 位置 | 注解 |
|---|---|
| `com.neocat.OpenApiConfiguration` | `@OpenAPIDefinition` + `@Info`、全局 `@SecurityScheme`（APIKEY / cookie / `NC_SESSION`） |
| 12 个 Controller | `@Tag(name, description)` |
| 全部对外端点 | `@Operation(summary, description, operationId)` |
| 匿名端点 | `@SecurityRequirements`（登录、初始化状态、初始化） |
| 路径/查询参数 | `@Parameter` |
| 10 个 HTTP DTO 文件 | 类级 `@Schema(description)` + 关键字段 `@Schema`（可空、枚举、单位、精度） |
| 错误响应 | `@ApiResponse`，只标注该端点真实适用的状态码 |

`@RequestBody` 等仍是 Spring 绑定注解；Protobuf 端点用 `@Content(mediaType = "application/x-protobuf")` 声明二进制请求与响应，媒体类型与报文体不变。

## 未改变的行为

- 未改 URL、HTTP 方法、状态码、错误码、JSON 结构与数组形态。
- 未新增字段、校验或端点；`@Schema` 不是运行时校验。
- 会话拦截器仍只拦截 `/api/**`；文档路径不在其中，因此默认关闭，开启后需部署侧自行限制访问（已写入 `03-api-contract.md` §1.4）。

## 离线证据

| 验证 | 结果 |
|---|---|
| IDEA `build_project` | 成功，无编译错误 |
| IDEA `lint_files`（新增/修改的 Java） | 无错误级问题 |
| `mvn -o -f backend/pom.xml clean test` | 1380 项，10 failures，0 errors |
| 失败身份与 2026-10-08 基线比对 | 逐项 `(class, name, kind)` 完全一致：`ModuleBoundarySpec` 6 项、`PrdAcceptanceTraceabilitySpec` 4 项 |
| 新增 `OpenApiContractSpec` | 3 项全部通过：真实生成 `/v3/api-docs`，断言全部端点均有 tag、summary 与唯一 `operationId`，全局会话 Cookie 安全方案与 3 个匿名端点，`LoginRequest`/`AlertConditionDraft`/`CardSeriesResponse` schema 与阈值精度、`isUndefined` 数组契约 |
| `node scripts/check-coding-standards.mjs` | 通过，451 个手写 Java 文件；新增 `@Tag`/`@Operation`/`@Schema` 与 `operationId` 唯一性检查 |
| `node --test scripts/check-coding-standards.test.mjs` | 10/10 通过（新增接口文档注解用例） |
| `node scripts/check-type-imports.mjs` | 通过，572 个文件 |
| `node --test scripts/type-imports.test.mjs` | 9/9 通过 |
| 端点/错误码/协议检查 | 64 后端端点对 39 前端 mock 端点、44 错误码、唯一 proto 19 个生成类两端一致 |
| 后端、SDK `-DskipTests package` | 均成功 |
| `git diff --check` | 通过 |

后端 10 个既有失败未顺带修复；真实 MySQL / ClickHouse / Apollo 装配与文档入口的部署访问控制在现场未验收。
