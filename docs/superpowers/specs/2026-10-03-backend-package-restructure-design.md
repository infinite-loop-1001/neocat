# 后端包结构细分设计

日期：2026-10-03
状态：已实施并通过离线验证（分包与部署文档优先，Metric 空数据优化随后）

## 1. 目标与边界

**目标**：把 `backend` 各模块过大的 `domain` / `infra` 包按职责细分到子包，使每个包的类数量可控、职责单一、便于定位与维护；同步细分测试包；更新受影响文档。

**不做**：不改业务逻辑、HTTP 契约、Protobuf 协议、SQL/DDL、错误码、运行参数；不新增或放宽模块依赖；不改变 `applicationModules.verify()` 的既有边界结论。

**关键事实（已核实）**：

- 全库**无**顶层 `package-private` 类；`class` 内的包级私有成员**无**跨文件引用。此前疑似的可见性问题全部来自 `interface`（成员隐式 `public`）。因此跨子包移动类型不会破坏编译可见性。
- Spring Modulith 1.2.10 的包级 `@NamedInterface` **只导出标注包直接包含的 public 类型**。`domain` 被子模块引用，拆分后须保留原具名接口及 public 类型集合（含 public 嵌套类型）。
- **实施验证修正**：1.2.10 的 `ofAnnotatedPackages` 不归并多个同名包接口，`getByName` 只返回第一项，不能依赖 `NamedInterfaces.and` 合并这些兄弟包。新子包使用类型级同名注解，`ofAnnotatedTypes` 按名字聚合，再与保留的原包级接口合并；不修改模块依赖白名单。
- 模块仍由 `com.neocat` 的直接子包界定（`ApplicationModuleDetectionStrategy` 默认策略），加深子包层级不产生新模块。

## 2. 细分规则

1. **保留一级分层**：`api/http`、`api/internal`、`domain`、`infra` 不变。
2. **按职责加二级子包**：例如 `query/domain/metric`、`alert/infra/listener`。
3. **同名接口导出**：拆分的具名接口包，新子包原有 public 类型与 public 嵌套类型使用同名类型级 `@NamedInterface`；子包 `package-info.java` 仅记录接口归属，原包级注解保留。导出全集由回归测试锁定。
4. **优先聚合相关类**：一般子包至少 2 个类；职责独立的上下文、适配器、投影与调度入口允许单类包，不为凑数量混合职责（具体以 §3 清单为准）。
5. **不拆小于 8 个类的包**（`common/config/impl`、`common/queue/impl`、`platform/infra`、`catalog/infra` 等保持原样），除非存在明确的二分职责。
6. **模块根包可只留装配类**：`<module>/infra` 与 `<module>/api/http` 允许只保留 `*Wiring` / `*Controller`，这类根包不算"单类子包"。
7. **测试目录与 package 镜像被测类所在的生产包**；跨包规格选主要被测类的包。
8. **清理残留空目录**：`backend/src/main/java/com/neocat/web/api`。
9. **`package-info.java` 保留**：原具名接口包级声明保留；新子包按 §2.3 用类型级注解，避免 1.2.10 重复同名包接口。

## 3. 模块级映射

### 3.1 common

| 新包 | 类 | 具名接口 |
|---|---|---|
| `common.error`（保留） | `ErrorCode`, `NeocatException` | `error` |
| `common.error.exception` | `AuthenticationException`, `AuthorizationException`, `BusinessRuleException`, `ConflictException`, `ExpiredException`, `IngestException`, `ResourceNotFoundException`, `ValidationException` | `error` |
| `common.http.error` | `ApiError`, `ApiExceptionHandler`, `ErrorCodeMapping` | `http` |
| `common.http.context` | `RequestActor` | `http` |
| `common.time.clock` | `ClockProvider`, `SystemClockProvider` | `time` |
| `common.time.range` | `RangeSpec`, `RangeParams` | `time` |
| `common.time.bucket` | `Bucket`, `Granularity`, `TimeBucketResolver`, `DefaultTimeBucketResolver` | `time` |
| `common.config`, `common.config.impl`, `common.queue`, `common.queue.impl` | 不变 | `config` / `common-config-impl` / `queue` / `common-queue-impl` |

### 3.2 identity

| 新包 | 类 | 具名接口 |
|---|---|---|
| `identity.domain.account` | `Account`, `AccountChangeResult`, `AccountRepository`, `AccountService`, `AccountStatus`, `Role` | `identity` |
| `identity.domain.auth` | `AuthenticationService`, `LoginResult`, `LoginTarget`, `PasswordHasher`, `ServiceAvailability`, `SessionGuard` | `identity` |
| `identity.domain.session` | `Session`, `SessionRepository`, `AccessHistoryRepository` | `identity` |
| `identity.infra.jdbc` | `AccountMapper`, `AccountRepositoryAdapter`, `AccessHistoryMapper`, `AccessHistoryRepositoryAdapter`, `SessionMapper`, `SessionRepositoryAdapter` | — |
| `identity.infra.adapter` | `AccountDirectoryService`, `SuperAdminProvisioningAdapter`, `BCryptPasswordHasher` | — |
| `identity.infra`（保留） | `IdentityWiring` | — |
| `identity.api.http.auth` | `AuthWhitelist`, `SessionInterceptor`, `SessionWebConfiguration` | — |
| `identity.api.http`（保留） | `IdentityController`, `UserAdminController` | — |
| `identity.api.internal` | 不变 | `internal` |

### 3.3 organization

| 新包 | 类 | 具名接口 |
|---|---|---|
| `organization.domain.tree` | `OrgNode`, `OrgNodeRepository`, `OrgTreeService`, `DeletionPreview` | `organization` |
| `organization.domain.membership` | `MembershipRepository`, `EffectiveLeafRepository`, `OrgMembershipService` | `organization` |
| `organization.domain.lifecycle` | `OrgLifecycleService`, `OrgDeletionRequested`, `OrgResourceGateway` | `organization` |
| `organization.infra.jdbc` | `OrgNodeMapper`, `MembershipMapper`, `EffectiveLeafMapper`, `OrgNodeRepositoryAdapter`, `MembershipRepositoryAdapter`, `EffectiveLeafRepositoryAdapter` | — |
| `organization.infra.projection` | `OrgResourceProjection` | — |
| `organization.infra.adapter` | `OrganizationAccessService` | — |
| `organization.api.*` | 不变 | `internal` |

### 3.4 platform

| 新包 | 类 | 具名接口 |
|---|---|---|
| `platform.domain.profile` | `PlatformProfile`, `PlatformProfileRepository`, `PlatformService`, `SlowThresholds` | `platform` |
| `platform.domain.channel` | `ChannelConfig`, `ChannelConfigRepository`, `ChannelType` | `platform` |
| `platform.domain.init` | `InitRequest`, `SuperAdminProvisioner` | `platform` |
| `platform.infra`, `platform.api.*` | 不变 | `internal` |

### 3.5 catalog

| 新包 | 类 | 具名接口 |
|---|---|---|
| `catalog.domain.entry` | `ServiceEntry`, `InstanceEntry` | `catalog` |
| `catalog.domain.report` | `ReportKind`, `TimeRange`, `SeriesPresence` | `catalog` |
| `catalog.domain.service` | `CatalogService`, `CatalogRepository` | `catalog` |
| `catalog.infra`, `catalog.api.*` | 不变 | `internal` |

### 3.6 ingest

| 新包 | 类 | 具名接口 |
|---|---|---|
| `ingest.domain.tree` | `MessageTree`, `RawNode`, `NodeKind`, `MetricValue`, `HeartbeatValue`, `RemoteCallValue`, `ExceptionValue` | `tree` |
| `ingest.domain.validation` | `TreeValidator`, `ValidationOutcome`, `FingerprintCalculator`, `LatenessPolicy` | `tree` |
| `ingest.domain.idempotency` | `IdempotencyService`, `IdempotencyStore`, `IdempotencyDecision`, `HistoricalFingerprintLookup` | `tree` |
| `ingest.domain.receive` | `IngestService`, `IngestBatch`, `IngestResult`, `IngestStatus`, `CatalogGateway`, `QualityEventSink` | `tree` |
| `ingest.infra.protocol` | `IngestRequestMapper`, `IngestResponseMapper` | — |
| `ingest.infra`（保留） | `HistoricalFingerprintAdapter`, `InMemoryIdempotencyStore`, `IngestQueueConfiguration`, `IngestWiring`, `JdbcQualityEventSink` | — |

`analysis` 依赖 `ingest :: tree`，实际引用集中在 `ingest.domain.tree`；所有迁出 public 类型标注同名接口以保持集合不变。

### 3.7 analysis

| 新包 | 类 | 具名接口 |
|---|---|---|
| `analysis.domain.analyzer` | `Analyzer`, `RealtimeConsumer`, `FanOutResult`, `SlowThresholdProvider`, `TransactionAnalyzer`, `EventAnalyzer`, `ProblemAnalyzer`, `HeartbeatAnalyzer`, `MetricAnalyzer`, `ProblemCategory` | `analysis` |
| `analysis.domain.dependency` | `DependencyAnalyzer`, `DependencyDirection`, `DependencyEdge` | `analysis` |
| `analysis.domain.bucket` | `MinuteBucket`, `MinuteBucketSource`, `HourlyReportStore`, `ReportBucketSinkPort`, `AggregatedRow`, `AggregationLevel`, `AggregationRoller`, `DurationDistribution`, `SeriesKey`, `SeriesKind` | `analysis` |
| `analysis.domain.metric` | `MetricLabels`, `MetricHourRank`, `MetricLabelMetadata` | `analysis` |
| `analysis.domain.schedule` | `ReportScheduler`, `ReportSnapshotSql` | `analysis` |
| `analysis.infra.store` | `InMemoryHourlyReportStore`, `InMemoryMetricHourRank`, `InMemoryMetricLabelMetadata`, `MinuteBucketReader` | — |
| `analysis.infra.jdbc` | `JdbcReportBucketSink`, `MetricMetadataFlushJob` | — |
| `analysis.infra.job` | `ReportSchedulerJob`, `RealtimeConsumerLoop` | — |
| `analysis.infra.adapter` | `PlatformSlowThresholdAdapter` | — |
| `analysis.infra`（保留） | `AnalysisWiring` | — |

### 3.8 trace

| 新包 | 类 | 具名接口 |
|---|---|---|
| `trace.domain.tree` | `TraceTree`, `TraceNode`, `TraceTreeNode`, `TraceRelation`, `TraceAssembler`, `NodeAvailability`, `RawTreeStore` | `trace` |
| `trace.domain.sample` | `Sample`, `SampleQuery`, `SampleService` | `trace` |
| `trace.infra.clickhouse` | `ClickHouseConnection`, `ClickHouseRawTreeStore`, `RawTreeQuery`, `JdbcRawTreeQuery`, `JsonTreePayloadCodec` | — |
| `trace.infra.adapter` | `StoredFingerprintService` | — |
| `trace.infra`（保留） | `TraceWiring` | — |

### 3.9 query

| 新包 | 类 | 具名接口 |
|---|---|---|
| `query.domain.series` | `Point`, `Series`, `Quality`, `QualityInput`, `QualityResolver`, `MomKind`, `MomAligner` | `query` |
| `query.domain.stat` | `Stat`, `StatCalculator`, `PercentileMerger` | `query` |
| `query.domain.report` | `ReportRow`, `ReportTableService`, `MachineRow`, `MachineView`, `MachineViewBuilder`, `RangeResolver`, `DependencyQueryService` | `query` |
| `query.domain.metric` | `MetricCountService`, `MetricFilters`, `MetricQueryService` | `query` |
| `query.infra.port` | `ReportDataPort`, `SamplePort`, `MetricMetadataPort`, `EmptySamplePort` | — |
| `query.infra.datasource` | `ClickHouseReportDataPort`, `ClickHouseReportQuery`, `JdbcClickHouseReportQuery`, `HourlyReportDataPort`, `JdbcMetricMetadataPort`, `ReportDataPortRouter` | — |
| `query.infra.service` | `ReportPointsService`, `SeriesDataLookupService`, `ReportSeriesPresenceAdapter` | — |
| `query.infra`（保留） | `QueryWiring` | — |
| `query.api.*` | 不变 | `internal` |

### 3.10 dashboard

| 新包 | 类 | 具名接口 |
|---|---|---|
| `dashboard.domain.card` | `Card`, `CardService`, `CardPoint`, `CardEvaluator`, `CardSeriesService`, `CardDimensionService`, `CardDimensionView`, `CardDrillRequest`, `ThresholdLine`, `AlertableTarget` | `dashboard` |
| `dashboard.domain.formula` | `Formula`, `FormulaParser`, `Unit` | `dashboard` |
| `dashboard.domain.dashboard` | `Dashboard`, `DashboardRepository`, `DashboardService` | `dashboard` |
| `dashboard.domain.event` | `CardEvent`, `CardEventPublisher` | `dashboard` |
| `dashboard.domain.access` | `OrgAccessGateway`, `CardInputSource` | `dashboard` |
| `dashboard.infra.jdbc` | `DashboardMapper`, `DashboardRepositoryAdapter` | — |
| `dashboard.infra.service` | `CardResultsService`, `ReportCardInputSource`, `StoredCardReferences` | — |
| `dashboard.infra.listener` | `OrgDashboardDeletionListener`, `SpringCardEventPublisher` | — |
| `dashboard.infra.adapter` | `OrganizationAccessAdapter` | — |
| `dashboard.api.*` | 不变 | `internal` |

### 3.11 alert

| 新包 | 类 | 具名接口 |
|---|---|---|
| `alert.domain.rule` | `AlertRule`, `AlertRuleRepository`, `AlertRuleService`, `AlertLifecycleService`, `Condition`, `Comparator`, `Combinator`, `AlertScope`, `AlertTarget`, `AlertChannel` | `alert` |
| `alert.domain.engine` | `AlertEngine`, `AlertWindowState`, `NotificationDispatcher`, `AlertNotification`, `PreviewService`, `PreviewResult`, `MinutePointSource`, `ChannelAvailability`, `Notifier`, `DeliveryLogger` | `alert` |
| `alert.domain.recipient` | `RecipientService`, `RecipientEvent`, `RecipientGateway` | `alert` |
| `alert.infra.jdbc` | `AlertMapper`, `AlertRuleRepositoryAdapter` | — |
| `alert.infra.adapter` | `ConfiguredChannelAvailability`, `RecipientEligibilityAdapter`, `ReportMinutePointSource`, `RuntimeDeliveryLogger`, `UnsupportedExternalNotifier` | — |
| `alert.infra.listener` | `CardChangeListener`, `OrgAlertDeletionListener`, `RecipientEventListener` | — |
| `alert.infra.job` | `AlertEvaluationJob` | — |
| `alert.api.http` | 不变 | — |

### 3.12 不细分

`protocol/ingest/v1`（生成类）、各模块 `api/internal`、上表标注"不变"的小包。

## 4. 测试映射

- 测试目录与 `package` 改为被测类所在的生产子包，例如 `query/domain/MetricQuerySpec.groovy` → `query/domain/metric/MetricQuerySpec.groovy`。
- 修正既有目录/包名不一致：`core/config/RuntimeConfigSpec` → `common/config/`，`core/queue/BoundedDropQueueSpec` → `common/queue/`，`core/time/TimeBucketResolverSpec` → `common/time/bucket/`。
- `web/*` 契约规格（`ApiContractSpec`、`ErrorModelSpec`、`FieldLevelContractSpec`、`SessionInterceptorSpec`）保持 `com.neocat.web` 目录与包（跨域 HTTP 契约）。
- 更新 `PrdAcceptanceTraceabilitySpec` 的 `TRACEABILITY` 与 `SUPPORTING_SPECS` 路径，使其指向移动后的实际目录。
- `CoreWiringConfiguration`、`CoreWiringSpec` 的导入与随机制类型名同步更新。

## 5. 必须同步的非 Java 引用

| 位置 | 内容 |
|---|---|
| `backend/src/main/resources/mapper/{alert,catalog,dashboard,identity,organization,platform}/*.xml` | `namespace` 与 `resultMap/parameterType` 的类全名（含 `$嵌套类型`） |
| `docs/neocat-technical-design/03-api-contract.md` | `common/http/ErrorCodeMapping.java` → `common/http/error/ErrorCodeMapping.java` |
| `docs/development/middleware-docker-deployment.md` | `trace/infra/ClickHouseConnection.java` → `trace/infra/clickhouse/ClickHouseConnection.java` |
| `docs/neocat-technical-design/02-modules.md` | 补充子包约定 |
| `docs/neocat-technical-design/11-modulith-migration-status.md` | 记录本次细分 |

`scripts/check-error-codes.mjs`（读 `common/error/ErrorCode.java`）、`check-contract-alignment.mjs`（扫描 `*Controller.java` 与 `/api/http/`）、`check-protocol-drift.mjs`（`protocol/ingest/v1`）不受影响：`ErrorCode`、各 Controller、生成协议包均未移动。

## 6. 保持不变的对外约定

- 具名接口集合（`query`、`analysis`、`tree`、`trace`、`catalog`、`identity`、`organization`、`platform`、`dashboard`、`alert`、`error`、`http`、`time`、`queue`、`config`、`internal`、`v1`…）与 `@ApplicationModule.allowedDependencies` 声明。
- 所有 HTTP 路径、请求/响应结构、错误码数值与消息模板。
- MyBatis 语句 id、表名与列名、SQL 文本。
- Protobuf 字段号与生成类包名。

## 7. 验证

1. `mvn -o -q -DskipTests compile` 通过（先于测试，快速暴露包引用遗漏）。
2. `mvn -q verify`：backend Groovy 单测全绿，含 `ModuleBoundarySpec`（模块识别、依赖方向、控制器位置、跨模块 infra/http 引用禁令、`displayName`）。
3. `node scripts/check-contract-alignment.mjs`、`check-error-codes.mjs`、`check-protocol-drift.mjs` 通过。
4. `grep` 确认无残留旧包名引用与空目录。
5. 不做数据库或浏览器验收（本次为纯结构重构，无行为变更）。

## 8. 风险与对策

| 风险 | 对策 |
|---|---|
| 遗漏 `@NamedInterface` 或重复同名包接口 | 新子包按类型聚合；`ModuleBoundarySpec` 同时校验接口名唯一性与原 public 类型全集（含嵌套类型） |
| MyBatis XML 类全名未同步导致启动期 Mapper 注册失败 | 步骤 5 列出全部 10 个 XML 并逐一核对；离线 JDBC 测试可捕获映射，但不能替代真实数据库往返 |
| 通配/静态导入未更新 | 编译失败即暴露；`import com.neocat.X.*` 展开为新子包通配 |
| `PrdAcceptanceTraceabilitySpec` 路径失效 | 同步更新路径表；该规格本身断言文件存在 |
| 大规模移动引入笔误 | 先编译后测试；不改任何方法体逻辑，仅改 `package`/`import`/注解 |

## 9. 后续

本次仅结构重构。部署文档的更新（十一张 ClickHouse 表、Metric/Heartbeat 迁移顺序、Heartbeat 20 项与 SDK 说明、构建与启动命令、验收与回退）已同步完成，见 `docs/development/middleware-docker-deployment.md`。离线验证结果与现场限制见 `docs/neocat-technical-design/11-modulith-migration-status.md`；运行数据库未迁移。
