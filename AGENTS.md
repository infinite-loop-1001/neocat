# AGENTS.md — NeoCat

NeoCat 由三部分组成：Spring Boot 3.3 + Spring Modulith 后端、Java SDK、Vue 3 / Vite 前端。
产品事实源（32 条端到端链路）在 `docs/neocat-product-design-v2/`；技术方案在
`docs/neocat-technical-design/`；现场操作手册在 `docs/development/`。

## 编码规范（必须遵守）

修改代码、测试或依赖前，必须阅读并遵守 [.agents/coding-standards/README.md](.agents/coding-standards/README.md)，
以及其中引用的 Java、HTTP API 和 MySQL 锁规范。强制项不得绕过；建议项的必要例外要说明理由并提供测试。
全仓重构保持现有 JSON / Protobuf 契约，Controller 使用专用 DTO、Convert 与 `ResponseEntity<T>`。
类型引用（含注解与测试）使用 import + 简单类名，仅同一源文件确有同名类型冲突时保留必要的全限定名。
未经用户明确要求，不执行 Git 暂存、提交、推送或调整已有暂存状态；用户自行 review 后操作 Git。

## 工具优先级：优先使用 IDE MCP

本仓库在 IntelliJ IDEA 中打开，并接入了 IDE MCP（`neocat-mcp`）。查找、阅读、改符号和验证时
**优先用它**，只有它做不到时再退回 shell / 通用文本工具。

| 任务 | 优先用 | 不要用 |
|---|---|---|
| 找类/方法/字段 | `search_symbol` | 猜路径、盲搜 |
| 谁调用它 / 它调用了谁 | `analyze_calls`（`INCOMING_CALLS` / `OUTGOING_CALLS`） | 文本搜索拼调用图 |
| 正则 / 文本 / 文件搜索 | `search_regex` / `search_text` / `search_file` | `grep` / `find` |
| 读文件（含 jar 内与反编译） | `read_file` | `cat` |
| 目录结构 | `list_directory_tree` | `ls` / `tree` |
| 符号声明与文档 | `get_symbol_info` | 猜签名 |
| 改符号名 | `rename_refactoring` | 全局文本替换 |
| 格式化 | `reformat_file` | 手工排版 |
| 编译校验 | `build_project` | 只看文本改动 |
| 静态问题 | `lint_files` / `get_file_problems` | 靠肉眼 |
| 跑命令 | `execute_terminal_command`（需用户确认） | 另开 shell |
| 运行时取值 | `xdebug_*` | 临时加 print |

- 编辑后用 `build_project` / `lint_files` 验证，再跑 Maven 或 npm 测试。IDE 解析基于真实类型，
  比文本匹配可靠；文本搜索结果不能当作类型绑定正确的证据。
- Groovy/Spock 规格是动态类型，方法改名不是编译错误，只在运行时失败：用 `rename_refactoring`
  连带更新引用，优于文本替换。
- IDE MCP 的数据库工具（`execute_sql_query`、`preview_table_data` 等）面向真实连接，属于现场操作：
  默认不使用，也不把它的结果当作离线测试证据。
- MCP 不可用时才退回 shell，并在结论里说明该证据来自文本而非 IDE 解析。

## 仓库结构（不明显的地方）

- **没有根 `pom.xml`，也没有 `mvnw`。** `backend/` 与 `client-java/` 是两个独立 Maven 模块，
  没有父 POM。用 `-f` 分别构建。
- 文档里可能仍写着 `./mvnw -pl backend ...` —— 那是过期的，用下面的命令。
- **不是 Git 仓库。** 无法提交、diff 或推送。
- 后端模块位于 `backend/src/main/java/com/neocat/{identity,organization,platform,catalog,ingest,analysis,trace,query,dashboard,alert}`，另有 `common`、`protocol`。模块边界由代码强制，不只是约定（见下文）。
- `proto/neocat/ingest/v1/ingest.proto` 是上报协议的**唯一事实源**。`backend` 与 `client-java` 都从它生成 Java。禁止新增第二份 `.proto`。

## 构建与测试

在仓库根执行。离线（`-o`）是常规且受支持的。

```bash
mvn -f backend/pom.xml test
mvn -f client-java/pom.xml test
mvn -f backend/pom.xml -DskipTests package
mvn -f client-java/pom.xml -DskipTests package

cd front && npm test && npm run flow && npm run build
```

运行单个后端规格（Surefire 只收 `*Spec`，排除 `*IT`）：

```bash
mvn -o -f backend/pom.xml test -Dtest=ModuleBoundarySpec -Dsurefire.failIfNoSpecifiedTests=false
```

### 跨端检查（改动 API、错误码或协议后执行）

```bash
node scripts/check-contract-alignment.mjs   # 前端端点 vs 后端控制器
node scripts/check-error-codes.mjs          # ErrorCode.java vs front/src/api/error-codes.ts
node scripts/check-coding-standards.mjs     # 禁用项、重复 key、成员间距与编译参数
node --test scripts/check-coding-standards.test.mjs  # 检查器自身规格（需要 JDK）
node scripts/check-protocol-drift.mjs       # 需先打包两端
```

`check-protocol-drift.mjs` 读取生成源码，所以要先跑上面两条 `package`。
Protobuf 字段号不一致**不会报错**，而是静默解析错位导致数据错乱 —— 这就是该检查存在的理由。

## 测试硬约束（见 `07-config-and-testing.md` §2）

- 测试必须在**无中间件、无网络**下全绿：禁止连接 MySQL、ClickHouse、Apollo、Redis、SMTP、钉钉、飞书。
- 禁止用 H2 或 Testcontainers 冒充真实中间件。
- Mapper/DAO 连通性留待人工（`*IT`，被 Surefire 排除）。目前没有任何 `@Tag("integration")` 测试。
- 后端当前时间统一经公共静态 `TimeProvider` 获取，不通过 Spring 注入 Clock，TimeProvider 也不提供设置时钟的方法；Groovy 单测直接给其私有静态时钟字段赋值，结束后赋回 `Clock.systemUTC()`，不使用线程隔离或恢复作用域，领域代码禁止 `Instant.now()`。
- 动态 Apollo 配置用 `public static volatile` 字段；`SpockConfig.groovy` 关闭并行执行，
  因为测试会通过 `StaticConfigFixture` 修改共享静态状态。
- 外部通知未实现（`UnsupportedExternalNotifier` 直接抛异常）。禁止断言投递成功。

## 容易踩的坑

- **Apollo 是启动必需项。** `ApolloConfigGuard` 在缺任一必需键时启动失败。
  必需键 = `FRAMEWORK_KEYS` 加上 `ApolloConfigGuard.DYNAMIC_CONFIG_TYPES` 中 8 个类的每个
  `@ApolloStaticValue` 字段。**新增配置类必须登记进去**，否则不受校验。
- **领域对象是普通类 + Lombok，不再是 `record`。** 字段 `x` 暴露 `getX()`（`boolean` → `isX()`）。
  手写的行为方法保留原名（例如 `ParseOutcome.valid()`、`TraceTreeNode.messageId()`）。
- **`-parameters` 编译参数是关键依赖。** 普通类的 `@RequestBody` 绑定靠它；删掉会让 JSON 字段
  静默变成 `null`，而 Groovy mock 单测**测不出**。两个 POM 里都不要移除。
- **前端没有 `/api` 代理。** `front/vite.config.ts` 只设了 `server.port`。因此
  `VITE_USE_MOCK=false npm run dev` 在补代理（或同源反向代理）之前是坏的。
  前端默认走 mock（`VITE_USE_MOCK !== "false"`）。
- **普通 `package` 产物不可直接运行。** 可执行 jar 要显式 repackage：
  `mvn -f backend/pom.xml -DskipTests package org.springframework.boot:spring-boot-maven-plugin:3.3.13:repackage`。
- **模块边界由测试强制。** `ModuleBoundarySpec` 校验 `allowedDependencies`、循环依赖、具名接口，
  以及 `named-interface-types.properties` 基线。改包名/`@NamedInterface` 可能让它失败。
- Groovy/Spock 规格是动态类型 —— 方法改名不是编译错误，只在运行时失败。
  优先用编译器驱动修正，而不是盲目文本替换。

## 文档地图

- `docs/neocat-product-design-v2/` — 产品行为事实源（32 条链路）。分域文档不得重新定义共享术语。
- `docs/neocat-technical-design/` — 架构、模块、接口契约、建表脚本（`05-mysql-schema.sql`、`06-clickhouse-schema.sql`）、配置与测试。
- `docs/development/integration-test-runbook.md` — T00、T01–T32（每条链路一个）与 T33–T39 现场用例；当前全部为 `NOT_RUN`。
- `docs/development/middleware-docker-deployment.md` — 异机逐容器中间件部署。
- `docs/development/mysql-lock-verification.md` — 新锁表迁移与真实双连接锁验收，当前 NOT_RUN。
- `docs/development/coding-standards-refactor-verification.md` — 编码规范重构的离线证据与保留缺口。
- `docs/neocat-technical-design/11-modulith-migration-status.md`、`12-apollo-static-config-status.md` 与 `13-enum-extraction-status.md` — 当前状态与已知缺口。

## 状态诚实原则

离线单测与前端 mock 动线通过，但**真实 MySQL / ClickHouse / Apollo / Spring 装配 / 外部投递
从未在真实端口上跑过**。不要把系统描述为生产就绪，也不要暗示中间件已验证。
