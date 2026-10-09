# HTTP API 编码规范

采用兼容方案：`ResponseEntity<专用 DTO>`，不增加 `{code,message,data}` 包装。

- DTO 与 Convert 位于各业务模块 `api/http/dto`、`api/http/convert`，不跨模块复用 HTTP DTO。
- DTO 按 [Java 通用规则](java.md#一种类型一个文件强制) 使用同名独立文件，不再集中声明于 `XxxDtos` 容器；该规则同样适用于非 HTTP 的生产类型。
- `dto` 下按接口用途建立职责子包，例如 `auth`、`user`、`card`、`series`、`metric`；请求与响应按业务用途放在一起，不机械分成两个大包。已有单一用途类型族无需继续细分，模块内固定成功响应可放在 `dto/common`，不得跨模块共用。
- 请求 DTO 仅承载接口数据，不包含 `toRule()`、`toCard()` 等领域转换行为。
- Convert 完成请求到领域参数、领域结果到响应 DTO 的转换；**必须使用 MapStruct**，禁止手写静态转换工具类。
- Spring 管理的 Convert 使用 `@Mapper(componentModel = "spring")`，Controller 等调用方通过构造函数注入，禁止在生产调用方使用 `Mappers.getMapper` 或静态单例替代注入。无 Spring 上下文的离线单测可使用 `Mappers.getMapper` 获取生成实现。
- 普通字段、嵌套 DTO 与集合元素映射交给 MapStruct 生成；字段名差异、默认值与特殊转换通过 `@Mapping` 及明确的辅助方法表达。仅对领域工厂调用、条件分支或自定义解析等无法直接声明的逻辑保留 `default` / 实例辅助方法，禁止只加 `@Mapper` 外壳而仍手写全部字段装配。不得为了生成映射绕过领域工厂或改变聚合不变量。
- MapStruct 处理器必须在构建中显式配置；与 Lombok 同用时配置 `lombok-mapstruct-binding`。生成实现不手工修改。转换改动须用离线测试验证默认值、枚举解析、可空字段与 JSON 结构，不能以编译成功代替契约验证。
- 禁止 Controller 直接暴露领域类、Map 拼装固定字段响应。动态键有实际业务语义时可以作为 DTO 内明确类型的字段。
- 列表与标量保持原 JSON 结构，不为「专用 DTO」给原有数组或标量增加外层对象。
- 统一返回 ResponseEntity，保留 URL、请求参数、状态码、错误码、ApiError、Cookie 与鉴权语义。
- `null`、省略字段、空集合不互换；缺数不得改为 0。
- Protobuf 上报继续使用生成的协议类型及 `ResponseEntity<byte[]>`，不能改为 JSON，协议仍由唯一 proto 生成。
- 当前时间来自公共静态 TimeProvider，不注入 Clock，也不提供设置时钟的方法；Groovy 单测直接给其私有静态时钟字段赋值，结束后赋回系统 UTC 时钟，不提供隔离或恢复作用域。对 DTO 实际执行 Jackson 绑定和序列化测试，不仅检查反射或 Mock 返回值。

## 接口文档（强制）

对外 HTTP 接口必须用 SpringDoc + OpenAPI 3 注解声明文档；只生成机器可读文档，**不引入 Swagger UI 页面**。

- 依赖只用 `org.springdoc:springdoc-openapi-starter-webmvc-api`，版本以 Spring Boot 兼容表为准并写入 POM 属性；禁止加入 `…-ui`、Swagger UI WebJar 或 Springfox。
- 文档入口 `/v3/api-docs` 默认关闭（`springdoc.api-docs.enabled=false`），需要时由部署显式开启；不得默认匿名放开文档入口。
- 每个 `@RestController` 类必须有 `@Tag(name=…)`，名称与 `description` 不能为空；同一子域的接口归入同一个 tag，tag 名稳定，不随端点增删变化。
- 每个对外端点必须有 `@Operation(summary=…)`，`summary` 用一句话说明业务行为，`operationId` 全局唯一且稳定；`description` 只补充名称写不下的关键语义（缺数、幂等、自动关闭、不可修改等），不重复字段名。
- 路径、查询、请求头参数用 `@Parameter` 说明含义、取值范围与单位；Spring 的绑定注解保持原样，不改为 Swagger 注解。
- 请求／响应 DTO 类必须有 `@Schema(description=…)`；字段级 `@Schema` 用于语义不明显的字段，写明可空、枚举取值、单位与数值精度。
- 成功状态码与非 2xx 语义用 `@ApiResponse` 声明实际可能的响应与 `ApiError`；只标注该端点真实适用的状态码，不机械复制同一组错误。
- 鉴权统一在 `@OpenAPIDefinition` 声明全局 `@SecurityScheme`（会话 Cookie `NC_SESSION`），匿名端点用 `@SecurityRequirements` 显式清空；文档不得声称不存在的鉴权方式。
- Protobuf 端点用 `@Operation` 的 `@RequestBody`／`@ApiResponse` 声明 `application/x-protobuf` 语义，方法参数仍是 Spring 的 `@RequestBody byte[]` 绑定。
- 文档注解只描述现有行为：不得借补文档新增字段、校验、状态码，或改变 URL、JSON 结构、鉴权与请求体格式；`@Schema` 不是运行时校验。
- 文档契约由离线回归验证：注解完整性由 `scripts/check-coding-standards.mjs` 检查，真实生成的 `/v3/api-docs` 由后端 `OpenApiContractSpec` 检查。
