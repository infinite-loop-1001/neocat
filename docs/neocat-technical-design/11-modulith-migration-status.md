# Modulith 模块迁移状态（更新至 2026-10-03）

本文件记录 `docs/superpowers/specs/2026-10-01-neocat-modulith-boundaries-design.md` 的实施证据与未完成项；不能将静态测试成功等同于生产启动成功。

## 已迁移

- HTTP 控制器按所属业务模块放入 `api/http`，账号与组织管理拆成两个 Controller；统一错误响应迁入 `common/http`，异常和时间/队列/配置迁入 `common`，原 `web`、`core` 顶层包与各业务模块的 `adapter` 包已移除。
- 原 `NeoCatModuleConfiguration` 删除；目录、上报、分析、查询、Trace、平台等在自有模块装配，通用运行参数与队列工厂归 `common`。`platformZone` 仅保留平台模块一份。
- `catalog/api/internal` 提供发现与登录所需的最小方法调用契约，`ingest/infra` 和 `identity/infra` 实现各自的领域端口。HTTP 鉴权的请求身份上下文仅暴露最小信息。
- `META-INF/app.properties` 只声明 `app.id`；旧 `application.properties` 和 `application-test.properties` 已删除。生产 `main` 不绕过 Apollo 客户端；启动后由 `ApolloConfigGuard` 校验必需键，缺失或非法时拒绝启动；生产参数占位符无默认值。
- `query` 的报表读取 Bean 已接入 `ReportDataPortRouter`，按平台时区的当前小时边界拆分内存与 ClickHouse 数据源；增加路由和在线配置测试。
- `ModuleBoundarySpec`、后端与 SDK 全量单测、错误码/协议/前端契约脚本、前端构建与 mock 动线通过。
- 2026-10-01 后续迁移：`backend/src/test/java` 中手写的 `Fake*`、`InMemory*` 与 `Recording*` 外部依赖替身已删除，领域/HTTP 规格以 Spock `Mock`/`Stub` 隔离，生产 `infra/InMemory*` 实现仍保留。生产七个 `MyBatis*Repository` 类名改为 `*RepositoryAdapter`；补充会话、组织成员/有效叶、组织资源投影、告警收件/通道/分钟点及其隔离单测。告警规则的通道选择独立于收件人持久化，避免收件人清空后丢失配置。离线测试、`ModuleBoundarySpec` 和 `scripts/check-contract-alignment.mjs` 通过；均不代表数据库或通知服务联调。

## 2026-10-03 职责子包细分与部署文档

- 按 [逐类设计](../superpowers/specs/2026-10-03-backend-package-restructure-design.md) 细分全部业务模块和 `common` 的大包，移动 252 个生产类和 87 个测试/夹具路径（其中两个测试仅纠正原 `core` 目录）；保留已有小包、启动入口、生成协议包与模块边界。生产引用、测试包、PRD 覆盖路径和 10 个 MyBatis XML 类全名同步更新。
- 实测发现 Modulith 1.2.10 多个同名包级接口不会归并：新职责子包改为类型级同名 `@NamedInterface`，包括原导出的 public 嵌套类型；原包声明保留。`ModuleBoundarySpec` 新增接口名唯一性及 25 个原具名接口的导出类型集合断言；模块根 `allowedDependencies` 与原声明逐字节一致，没有放宽边界。
- 与移动前快照归一化比对：生产 Java 仅改包/导入/FQN/具名接口注解，方法体不变；MyBatis XML 仅替换类型全名，SQL 不变；既有测试除包/引用和 PRD 路径外不改断言。新增 `MapperRegistrationSpec` 证明全部 10 个 XML 能离线注册，不证明 MySQL 执行或事务。
- 分包后 `mvn -q clean verify` 通过：backend **97 套件 / 1251 项**，client-java **2 套件 / 30 项**，零失败/错误/跳过。64 个后端路径与 39 个前端 mock 路径对齐，44 个错误码一致，两端 19 个协议生成类逐字节一致。干净构建必要：增量构建会残留移动前 class/旧 Spock 规格，不能拿增量结果替代证据。
- 部署与联调手册已同步十一张 ClickHouse 表、Metric/Heartbeat 增量迁移顺序及历史缺口、20 项采集边界、新 Metric count 路径、显式可执行 jar 打包、前端同源代理和回退门槛。固定版本 Boot `repackage` 已实测生成 JarLauncher/Start-Class、BOOT-INF 与 66 个依赖库；未改 POM 的业务运行配置。
- 本地未安装 Docker/ClickHouse 客户端，**未执行运行库迁移、生产启动、服务器部署或回退**。这些离线证据不消除下列现场阻断项。

## 2026-10-03 record 改为 Lombok 类

- 全部 **118 个 `record` 声明（91 个文件）**改为普通类：`private final` 字段 + 显式全参构造器 + `@lombok.Getter`/`@EqualsAndHashCode`/`@ToString`。使用 Lombok 1.18.46（`provided`，经 `annotationProcessorPaths` 显式绑定）。`client-java` 与前端不含 record 声明，未受影响。
- 访问器改为 **JavaBean 命名**：字段 `x` → `getX()`（`boolean` → `isX()`）。原先手写的行为方法（如 `AlertRule.organization()`、`Formula.unit()`、`ParseOutcome.valid()`、`TraceTreeNode.messageId()`、`AggregatedRow.bucketStart()`）保持原样，因为它们是显式声明的方法而非 record 组件访问器。同步改写了生产与测试中的约 1400 处调用点。
- **POM 新增 `-parameters` 是本次改动的必要前提**：record 由语言隐式携带构造器参数名，普通类不会。缺少它会令 Jackson 反序列化 `@RequestBody`（`UserDraft`/`CreateCardDraft`/`ChannelsRequest` 等）静默绑定为 `null`，而 Spock 单测是 Groovy mock，**无法发现**。通过 `maven-compiler-plugin` 3.13.0 的 `<parameters>true</parameters>` 显式开启，并实测 `MethodParameters` 属性存在、`UserDraft`/`LoginRequest`/`ChannelsRequest` 的 JSON 绑定与 `ApiError` 序列化正确。2026-10-03 后此配置在两端独立 POM 中保留，根 POM 已删除，见 12 文档。
- `sealed interface` 的嵌套子类型保持嵌套并显式 `final`（`implements` 隐式 permits）。非密封的普通转换类**不加 `final`**，否则 Spock 无法 mock（`AlertEngine` 已踩过此点）。
- `ApiContractSpec` 原断言 `ApiError.recordComponents`（record 专有反射 API）已改为经 `declaredFields` 校验 `code`/`message` 字段契约。
- 实测：backend **1251 项零失败**、`ModuleBoundarySpec` 13 项、client-java 30 项、三个契约脚本、前端构建与 47 项动线自检全部通过。**仅离线证据，不代表真实 JSON/HTTP 端到端、数据库或部署可用。**

## 未完成：不得视为生产就绪

1. **对内接口收紧**：历史 `domain` 包仍在 `@NamedInterface` 导出，`analysis` 直接读取 `ingest.domain` 的树模型，`query` 读取 `analysis.domain` 的聚合行，`dashboard/alert` 读取 `query.domain` 的统计类型等。需逐用例迁为模块 `api/internal` 契约与调用方 `infra` 翻译；`ApplicationModules.verify()` 通过只证明当前声明被遵守，不证明本目标已达成。
2. **生产 Bean 接线待真实验证**：`SeriesPresence`、`HistoricalFingerprintLookup`、`QualityEventSink`、`SlowThresholdProvider`、会话仓储、告警卡片分钟点、组织资源同步投影等已有实现和隔离测试；生产 `OrgResourceProjection` 仍须通过真实 Spring 上下文与 MySQL 验证 Bean 唯一性、同步事件传播和事务回滚。上线前需在已有数据库执行与 `05-mysql-schema.sql` 增补字段及 `nc_org_resource` 相对应的增量迁移，重建并核对投影，不能只运行全新安装脚本。`nc_alert_rule.channels` 须先从旧 `nc_alert_recipient.channel` 去重回填；旧规则已无收件人时无法推断原通道，需人工核对后再加非空约束。
3. **真实启动与联调**：Apollo 2.4 配置服务发现 URL、MySQL/ClickHouse DataSource 与 MyBatis Mapper 注册需在可用中间件环境验证；上报幂等、质量事件、通知投递、`VITE_USE_MOCK=false` 动线仍待联调。参见 `09-integration-checklist.md`。

下一阶段建议先补生产 Bean 端口与上下文启动验证，再逐模块把跨域领域类型迁入 `api/internal`，最后执行真实中间件验收。当前目录无 Git 仓库，无法对上述改动提交或提供差异审查记录。
