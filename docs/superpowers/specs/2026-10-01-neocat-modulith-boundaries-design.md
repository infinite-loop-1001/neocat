# NeoCat 业务模块优先的 Modulith 重构设计

日期：2026-10-01

## 背景与目标

当前 `com.neocat.web` 集中承载全部 HTTP 控制器、全局错误处理、会话鉴权及跨域 Bean 装配；`com.neocat.core` 混放共享契约；各业务模块的 `adapter` 被暴露为其他模块可依赖的具名接口。`web/NeoCatModuleConfiguration.java` 为多个业务模块创建 Bean，甚至与 `RuntimeConfiguration.java` 重复声明 `platformZone`。生产资源中的 `application.properties` 存有本地数据源配置、Apollo 关闭开关及默认参数。这些结构与“先业务模块，再模块内部分层”的边界不符。

目标：单个 `backend` Maven 工件中保持现有十个业务 Modulith 模块和独立协议模块；新增顶层 `common`，取消顶层 `web`、`core`；让每个业务模块分别拥有 HTTP 入口、对内调用契约、领域逻辑和基础设施/跨模块防腐层。仅以 Apollo 原生引导初始化配置（`app.id` 由 `META-INF/app.properties`/`-Dapp.id`，`apollo.meta` 由 `-Dapollo.meta`/`APOLLO_META`），所有运行配置来自启动时加载的 Apollo；不改变已有 HTTP/Protobuf 对外契约。

本设计不增加独立 Maven 子模块，不引入网络 RPC 框架；模块间的 RPC 在本应用中是通过公开接口的进程内方法调用。真实 MySQL、ClickHouse、Apollo 和通知服务端到端联调仍需可用环境。

## 模块与包边界

```text
com.neocat
├── common
│   ├── error       错误码、模板、异常子类
│   ├── http        统一 REST 错误响应、状态映射与异常处理
│   ├── config      跨模块运行参数契约及其实现
│   ├── time        共享时间抽象
│   └── queue       共享有界队列抽象及实现
├── identity
│   ├── api/http    身份、账号 HTTP 入口及会话拦截器注册
│   ├── api/internal 对其他模块公开的身份能力与 DTO
│   ├── domain      本模块的业务行为与端口
│   └── infra       持久化、外部系统及跨模块防腐适配
├── organization、platform、catalog、ingest、analysis、trace、query、dashboard、alert
│   └── api/http、api/internal、domain、infra（按实际能力建包）
└── protocol       现有上报协议模块，不改协议源
```

- 顶层业务包仍为 Spring Modulith 模块，`common` 为顶层共享 Modulith 模块；按功能拆分其内部包与具名接口，不将它变成引用业务模块的总装配层。`api/internal` 使用 `@NamedInterface` 显式导出；`api/http`、`domain`、`infra` 均不供其他业务模块直接 import。单模块内可引用本模块自身的领域类型。
- 调用方在**自己的** `infra` 实现领域侧端口：只调用被调用方 `api/internal` 的公开服务与契约 DTO，并完成模型翻译；不依赖被调用方的 Controller、领域内部类、Mapper 或存储实现。调用接口为同步本地调用；跨模块通知继续使用应用事件。避免以泛化的“万能 API”代替有明确用例的接口。
- 将已有各业务包的 `adapter` 统一迁为 `infra`；MyBatis、ClickHouse、上报协议转换、任务和跨模块防腐适配按职责归属。协议解码及 HTTP 入站映射若直接面向网络请求，则留在 `ingest/api/http`，不因为历史包名为 `adapter` 而强行放进 `infra`。
- `AdminController` 的账号功能划给 `identity/api/http`，组织与成员功能划给 `organization/api/http`；`ReportController` 划给 `query/api/http`，其余控制器随所属业务迁移。控制器仅依赖本模块服务/端口、`common` 能力及必要的本模块 DTO；HTTP URL、方法、响应结构与鉴权规则保持不变。
- `ApiError`、`ErrorCodeMapping`、统一异常处理迁至 `common/http`；`ErrorCode`、`NeocatException` 及子类迁至 `common/error`。会话拦截器、白名单与 `WebMvcConfigurer` 归 `identity/api/http`；不让 `common` 依赖身份模型。已有 `ErrorCode` 编号和消息模板、REST 数值 `code`、Protobuf v1 字符串 `code`、`OK`/`DUPLICATE`/`QUEUE_FULL` 等非异常结果标识不变。
- 更新每个业务模块的 `allowedDependencies` 与具名接口声明，删除对其他模块 `*-adapter` 和内部领域包的例外。跨模块共享业务模型需提取最小的公开契约；`ApplicationModules.verify()` 应检测越界和环依赖。

## Bean 归属与数据流

删除 `web/NeoCatModuleConfiguration.java` 和集中式 `web/RuntimeConfiguration.java`。领域行为仍由构造器显式声明依赖；可自行实例化的服务、仓储实现及任务在所属模块以 `@Service`、`@Repository`、`@Component` 注册并使用构造器注入。只为第三方对象、泛型 Bean 或不可直接标注的构造组合保留少量**模块内**配置工厂，不把集中配置逐字拆分成十份。共用时钟与运行参数等属于 `common`；业务特有资源在其拥有者模块内装配。

典型跨域流向：

```text
ingest/domain 的 CatalogGateway
  ← ingest/infra 的目录发现适配
  → catalog/api/internal 的发现接口

identity/domain 的 ServiceAvailability
  ← identity/infra 的服务可用性适配
  → catalog/api/internal 的目录/范围查询接口
```

`platformZone` 由 `platform` 提供唯一实现，经最小公开接口提供给所需模块；消除两处同类型 Bean 的重复声明。跨域 Bean 的构造不可偷渡到 `common` 或应用入口。现有 `query` 的历史 ClickHouse 读取与当前小时内存读取在迁移中须保留并核查其真实路由装配；不把仅注释声明的路由视为已完成。启动队列消费和定时任务的时序维持现有语义。

## Apollo-only 运行配置

> **已被 2026-10-03 方案取代（配置部分）**：自研 HTTP 在线预检与 `bootstrap.properties` 已删除，改为使用 Apollo 客户端原生引导，并以 `ApolloConfigGuard` 做启动门禁。见 `docs/superpowers/specs/2026-10-03-apollo-static-config-independent-build-design.md` 与 `docs/neocat-technical-design/12-apollo-static-config-status.md`。

生产资源仅保留 Apollo 客户端初始化的非业务引导参数（`META-INF/app.properties` 的 `app.id`）；不存放本地 `application.properties`、`application-test.properties` 或连接凭据。端口、MySQL/ClickHouse、MyBatis 参数、运行阈值及所需密钥由 Apollo 提供。`apollo.meta` 由部署环境注入，不作为运行配置的回退来源。平台用户维护的业务档案继续存在数据库中，并非本地配置文件。

在 Spring Boot 业务 Bean 初始化前，必须确认必需配置完整、格式合法；缺项或无效项明确启动失败，不静默使用 `@Value` 默认值、`DefaultRuntimeConfig.withoutApollo()` 或本地凭据。**注意**：Apollo 客户端在远端不可用时会退回本地缓存或空值并照常启动，因此启动门禁校验的是客户端已加载到 `Environment` 的配置完整性，而不是"绕过缓存直连远端"。若部署要求"必须在线且远端已发布"，须在联调项中单独验证，不能由本地缓存掩盖。

测试不依赖本地运行配置文件：领域测试继续使用替身，少量 Spring 上下文测试在测试代码中注入属性或模拟配置来源，另以隔离的 Apollo 服务/受控假服务验证启动失败与在线启动。若无真实中间件，不能把这些测试当成端到端联调的证据。

## 分阶段迁移与验收

1. **建立边界**：增加 `common` 功能包与 `api/internal` 的最小公开契约，更新依赖约束与架构测试；保持旧实现可编译，逐步撤掉 `web` 对各业务内部的直接依赖。
2. **迁适配与装配**：各模块迁 `adapter → infra`；先迁跨模块防腐实现，再让模块自有 Bean 使用注解和构造器注入，拆除集中工厂与重复 Bean。每一步核对模块依赖图、构造依赖和启动生命周期。
3. **迁 HTTP 与共享错误**：控制器/请求映射归各业务模块，拆分混合控制器；统一错误处理与错误类型入 `common`，鉴权归 `identity`；删除顶层 `web`、`core` 与业务 `adapter` 包。保证所有现有 URL、鉴权及错误载荷的契约不变。
4. **切 Apollo-only**：验证实际引导时序与无缓存在线读取；删除本地运行配置及默认连接凭据，缺必需配置、远端断连、断连且有缓存时均按预期拒绝启动。修改旧的“Apollo 不可用时默认启动”测试与文档。

每阶段运行编译、模块边界验证与相关 Spock 测试；最终运行全量后端及 SDK 测试、协议漂移/契约/错误码检查、前端构建与 mock 动线。补充 HTTP 映射与异常响应回归测试、模块间只依赖 `api/internal` 的静态检查、Bean 唯一性和 Apollo 正反例启动测试。仅在可用的 MySQL/ClickHouse/Apollo/通知环境按联调清单完成真实接口验证后，才宣布中间件联调通过。同步修订 `docs/neocat-technical-design/` 中过期的 web/core/adapter、依赖图与本地配置兜底描述。

## 决策与取舍

选择按阶段迁移而非一次性改包：当前 `web` 集中了 331 行跨模块装配，且存在重复 `platformZone` 声明及尚需核查的当前小时/历史查询接线；分阶段能以可执行边界测试定位问题。保留单一 Maven 工件和现有协议，不引入网络服务拆分。应用层只在确需跨域交互的地方暴露窄 `api/internal`，避免新公共模块演变为第二个总装配层。
