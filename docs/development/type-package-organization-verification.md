# 独立类型按职责归包与验证

日期：2026-10-09。用户确认「用途分包、相关类型成组」，纠正上一轮拆出后平铺的问题。

## 目录与范围

本轮迁移 **100 个类型**：61 个 HTTP DTO、17 个数据库行模型、20 个领域／内部接口结果、1 个 Metric 标签元数据类型、1 个 Convert 边界模型。包含必要关联结果类型，不扩大成全仓架构重构。

| 原职责 | 最终子包 |
|---|---|
| identity HTTP DTO | `dto/auth`、`dto/user`、`dto/common` |
| organization HTTP DTO | `dto/org`、`dto/member`、`dto/deletion`、`dto/common` |
| platform HTTP DTO | `dto/init`、`dto/profile` |
| dashboard HTTP DTO | `dto/dashboard`、`dto/card`、`dto/series`、`dto/common` |
| alert HTTP DTO | `dto/rule`、`dto/preview`、`dto/channel`、`dto/common` |
| query HTTP DTO | `dto/report`、`dto/metric`、`dto/heartbeat`、`dto/problem` |
| 数据库行模型 | 原基础设施职责包下 `row`，与 Mapper／Repository 分离 |
| 领域结果 | 所属领域职责包下 `result`；如 `engine/result`、`analyzer/result`、`card/result`、`report/result` |
| 平台内部接口结果 | `platform/api/internal/result` |
| Metric 标签元数据 | `analysis/domain/metric/metadata` |
| 卡片序列 Convert 边界模型 | `dashboard/api/http/convert/series` |

单一用途的 Trace DTO、已有领域事件／公式 AST／时间范围类型族保持完整；私有辅助类型、方法局部类型、Builder、生成代码、匿名类型、测试夹具不拆包。SDK 句柄没有迁移包，仅清理原同包冗余全限定引用。

规则更新于 `.agents/coding-standards/java.md` 和 `http-api.md`；设计记录为 `docs/superpowers/specs/2026-10-09-type-package-organization-design.md`。

精确清单为 `backend/src/test/resources/type-package-migrations.properties`，原独立文件迁移资源也同步更新为最终包名。调用方、MapStruct、Groovy、MyBatis XML、反射字符串与导出注解同步修改。子包仅整理职责，原模块依赖与导出职责不变。

## 验证证据

| 验证 | 结果 |
|---|---|
| IDEA `build_project`（最终串行执行） | 成功，无编译错误 |
| IDEA `lint_files` | 100 个归包文件 + 迁移规格共 101 个文件，无错误级问题，无超时遗漏 |
| HTTP／OpenAPI／迁移／MyBatis／精度定向测试 | 39/39 通过；首次运行使用 `clean test` 清除旧 class，最终重跑通过 |
| 后端全量离线测试 | 1384 项，10 failures，0 errors；`ModuleBoundarySpec` 6 项、`PrdAcceptanceTraceabilitySpec` 4 项，逐个 `(class, name, kind)` 与历史基线一致 |
| SDK 全量测试 | 31/31 通过 |
| 编码规范与类型引用检查 | 560 个 Java 文件、682 个 Java／Groovy 文件通过；4 处同名冲突保留必要全限定引用 |
| 两类检查器自身测试 | 20/20 通过 |
| 端点／错误码 | 后端 64 个端点对前端 39 个 mock 端点，44 个错误码对齐 |
| 两端 package 与协议漂移 | 均通过，19 个协议生成类逐字节一致 |
| Git 空白检查 | 暂存与未暂存 diff 均通过 |

迁移审查的文本辅助证据：100 个类型声明体在忽略 package、import、格式、注释及迁移相关导出注解后完全相同；61 个迁移 DTO 的显式 schema 名不变；7 个 MyBatis XML 文件只修改类路径，SQL 不变。该文本比较不替代 IDEA 编译与动态测试。

`StandaloneTypeMigrationSpec` 验证全部 116 个原拆分类型仍为所属模块的顶层文件；新增用例严格验证 100 个归包类型的文件路径、旧源码与旧 class 无残留。导出用例核对本轮迁移类型在所有具名接口中的导出集合，防止丢失或增加导出。

开始本轮时全仓导出基线已额外缺少 `trace::internal`、`common::common-config-impl`、`common::common-queue-impl`，并存在 organization 命名不一致；未借此任务修复或改写这些既有基线。原 `ModuleBoundarySpec` 保持报告全仓问题，不能把本轮类型导出回归通过称为模块边界全面通过。

主要日志位于会话临时目录 `…/T/opencode/neocat-packages-focused.log`、`neocat-packages-backend-final.log`、`neocat-packages-sdk-final.log`、`neocat-packages-checker-tests.log`。IDE 终端一次测试调用超时，测试命令改用 shell；编译与 lint 仍来自 IDE 类型解析。

## 边界与保留事项

- JSON、Protobuf、OpenAPI schema 名、URL、状态码、错误码、业务行为与 SQL 不变；Java 包名改变，外部直接引用这些类型的 Java 调用方需更新 import 并重新编译。
- 没有执行 Git 暂存、提交、推送或调整暂存区命令。不过 IDE MCP `apply_patch` 移动文件后观察到迁移文件显示为暂存 `R`／`RM`、新资源显示为 `A`；不能宣称暂存区未变，保留现状交由用户 review。
- 初始已暂存后删除的 `TimeScopedSpecification.groovy` 与 `TimeFixture.java` 未恢复、未调整。
- 前端未修改、未重跑 npm 全套。真实 MySQL／ClickHouse／Apollo／完整现场装配与外部投递仍未验证；离线结果不是生产验收。
