# 独立类型文件迁移与离线验证

日期：2026-10-09。

本文记录首轮独立文件迁移时的历史证据。后续按用途归包（100 个类型，保持类型族成组）及最新验证见 [type-package-organization-verification.md](type-package-organization-verification.md)；最终包名以迁移资源清单为准，不再要求与原容器同包。

## 范围与约束

- 先写入 `.agents/coding-standards/java.md` 的通用强制规则，并在 README 强制项 14 与 HTTP API 规范引用；不限于 HTTP 请求／响应。
- 适用于 backend、client-java 的手写生产具名类型。私有辅助类型及其内部实现、方法局部辅助类型、`NeoCat.Builder`、生成代码、匿名类、测试夹具除外；不将对外类型改成 private 规避规则。
- 拆出 **116 个类型**，其中 64 个 HTTP DTO、51 个后端非 HTTP 类型、1 个 SDK `RemoteCallHandle`。移除 7 个空 DTO 容器，HTTP DTO 文件现为 67 个。
- 非 HTTP 范围包括领域事件、公式 AST、时间范围、分析／查询结果、数据库行模型、队列适配器与内部接口结果。保留原业务模块与包，sealed 接口显式声明原子类型集合。
- 迁移清单为 `backend/src/test/resources/standalone-type-migrations.properties`；调用方、MapStruct、Groovy 测试、MyBatis XML 与反射引用同步迁移。
- 15 个 private 成员类型与 3 个局部辅助类型保留。当前源码本就没有 `NeoCat.Builder`，不新增它，也不把其他公开 SDK 类型当作 Builder 例外。

## 契约与兼容边界

- 116 个声明体在忽略格式、注释与类型引用路径后，与迁移前一致；全部 64 个拆出 DTO 的显式 `@Schema(name=…)` 不变。
- 字段、构造器、行为、JSON／Protobuf、URL、HTTP 状态／错误码、鉴权与业务 SQL 不变。SpringDoc 仍 API-only，不引入 Swagger UI。
- **Java 类型的源码限定名与二进制名发生变化**：例如 `NeoCat.RemoteCallHandle` → `RemoteCallHandle`。直接依赖旧嵌套类型的外部 Java 调用方需更新 import 并重新编译；本次不是 Java 二进制兼容迁移。
- 具名接口导出基线仅更新原嵌套类型的名称，不扩大导出职责或模块依赖。现有 organization／isOrganization 命名缺口仍由原 `ModuleBoundarySpec` 报错；新增迁移规格单独核对所有导出集合，不将该缺口描述为已修复。

## 离线证据

| 验证 | 结果 |
|---|---|
| IDEA `build_project` | 成功，无编译错误 |
| IDEA `lint_files`：116 个拆出文件 + 2 个新增／修改规格 | 无错误级问题；迁移调用方的更广检查仍有原有 Javadoc、SQL 注入语言检查及一般 warning，不宣称全仓 lint 零问题 |
| `mvn -o -f backend/pom.xml clean test` | **1383 项，10 failures，0 errors** |
| 失败身份比较 | 与原基线的 `(class, name, kind)` 完全一致：`ModuleBoundarySpec` 6 项、`PrdAcceptanceTraceabilitySpec` 4 项，无新增失败 |
| `StandaloneTypeMigrationSpec` | 3/3 通过：同包同名顶层文件、旧 class 无残留、具名接口导出集合与 sealed 集合；SDK 类型只检查源码，运行时由独立 SDK 模块验证 |
| 迁移＋事件＋HTTP DTO＋OpenAPI＋DecimalJson 定向测试 | 46/46 通过 |
| `mvn -o -f client-java/pom.xml test`（此前已 clean） | **31/31 通过**，包含 SDK 独立句柄回归 |
| `node scripts/check-coding-standards.mjs` | 通过：560 个手写 Java 文件、两个 POM，不扫描生成代码 |
| 检查器与类型引用检查器自身测试 | 20/20 通过（11 个编码规范用例 + 9 个类型引用用例） |
| 端点／错误码检查 | 64 后端端点对 39 前端 mock 端点、44 个错误码对齐 |
| 两端 `-DskipTests package` 与协议漂移检查 | 均通过，唯一 proto 的 19 个生成类逐字节一致 |
| `git diff --check` | 通过 |

完整测试日志保存在会话临时目录 `…/T/opencode/neocat-type-backend-final.log`、`neocat-type-sdk-final.log`；定向日志为 `neocat-type-focused.log`。

AST 独立文件检查覆盖 public／包级成员、接口内隐式 public 类型、枚举及同文件多顶层类型；测试覆盖 private／局部／匿名／Builder／生成路径／测试夹具除外项。文本与语法树等价比较只是辅助证据，类型绑定同时经过 IDEA 编译与 Maven 动态测试验证。

## 保留事项

- 没有顺带修复其他 `rules`／`fixme`；仅移除已兑现的结果类型抽取注释。
- 没有运行 Git 暂存、提交、推送或调整暂存区的命令。但迁移过程中观察到新增／删除文件进入暂存区（初始摘要 `3eff76b805c6f88a77fe23ef034d5892b37423fc83ceaf2dbd29c452f04f0e84`，后续摘要 `0e2b18ba2f0dffe8c03fe4682faee61d752bf85ebd1cbdd579b1cc313a071909`）；原因未确定，可能与 IDE 文件操作或外部操作有关。保留现状，请用户 review，**不能宣称暂存区未变**。
- 前端没有修改，未重跑 npm 全套。真实 MySQL／ClickHouse／Apollo、完整现场装配与外部投递均未验收；离线验证不代表生产就绪。
