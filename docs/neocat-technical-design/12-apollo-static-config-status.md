# Apollo 静态配置与独立构建迁移状态

日期：2026-10-03。方案见 `docs/superpowers/specs/2026-10-03-apollo-static-config-independent-build-design.md`。

## 实现

- 2026-10-03 在 `common/config/` 按用途拆分 8 个配置类；2026-10-08 迁至 `ingest/config`、`analysis/config`（Analysis / Report / Metric）、`trace/config`、`alert/config` 和 `query/config`（Query / Heartbeat）。28 个动态键仍全部用 `@Configuration(proxyBeanMethods=false)` + `@ApolloStaticValue` + `public static volatile` 字段（全大写 + 下划线）声明；键、类型与 Bean 名不变，无默认占位符。
- 删除 `RuntimeConfig`、`DefaultRuntimeConfig` 和运行参数快照 Bean。业务在使用点直接读取字段，没有 getter、构造器参数副本或循环外批参数副本。
- 删除自研的 HTTP 预检 `ApolloStartup` 与 `bootstrap.properties`：服务发现、远端读取、缓存回退都是 Apollo 客户端的职责，不再重复实现。`app.id` 由 `-Dapp.id`/`APP_ID`/`META-INF/app.properties` 提供，`apollo.meta` 由 `-Dapollo.meta`/`APOLLO_META` 提供。
- 保留一个不重复实现发现的启动门禁：Apollo 客户端在远端不可用时会退回缓存或空值并照常启动，`@ApolloStaticValue` 转换失败也只记日志留旧值，两者叠加会让缺失配置以 0/null 静默进入运行期。`ApolloConfigGuard` 在原生属性源安装后、业务 Bean 之前校验必需键，缺键或非法值直接拒绝启动。
- 2026-10-08：ApolloConfigGuard 与 RuntimeConfiguration 位于应用根包 `com.neocat`，避免 common 反向依赖领域配置；Clock Bean 已移除，后端统一通过公共静态 TimeProvider 取时。固定时间规格直接给 TimeProvider 的私有静态时钟字段赋值，结束后赋回系统 UTC 时钟，不使用隔离或恢复作用域。
- `@EnableApolloConfig` 注册原生处理器，common-apollo 自动配置注册静态字段处理器与监听器；消费配置的 Spring Bean 显式 `@DependsOn` 相应配置 Bean。
- 处理器 INFO 会打印原值，生产 `main` 把该类日志设为 ERROR；保留转换失败诊断。
- common-apollo 转换失败保留旧字段值；运行期范围校验与多字段原子更新不是本次新增保证。运行期须发布合法值；多命名空间避免定义相同键（依赖按变更事件原值更新，不仲裁重复键）。

## 全键消费审计

下表区分**字段可以刷新**与**业务真正消费**。共 17 个键有消费者，11 个键原先就未接入对应行为，迁移没有凭空实现新功能。

| 类/键（统一前缀 `neocat.`） | 实际读取与应用边界 |
|---|---|
| IngestConfig：`ingest.queue.capacity` | `IngestDropQueue.offer/capacity` 直接读；扩容立即接受更多数据，缩容保留已接受的数据，在排空至新容量之前拒绝新入队，仍计丢弃数 |
| `ingest.consumer-threads` | 消费循环每秒维护实际 worker；增容启动，减容完成当前批后退出；已在等待的 poll 使用原次超时直到返回 |
| `ingest.batch-size`、`ingest.batch-timeout-ms` | 每次 poll 直接读；正在执行的 poll 不追溯变更 |
| `ingest.max-trees-per-batch`、`ingest.max-batch-bytes`、`ingest.max-nodes-per-tree` | 同一 TreeValidator 每次校验直接读 |
| `ingest.idempotency-window-minutes` | 同一 IdempotencyService 写新条目时读；已有条目的 expiresAt 是业务到期状态，不重写历史 TTL |
| `ingest.accept-late-hours` | 迟到窗口、迟到小时刷新、Metric 元数据释放边界直接读 |
| `ingest.auth-token` | **未消费**：共享令牌鉴权尚未实现；启动门禁仍要求非空，不等于 SDK 可通过会话拦截 |
| AnalysisConfig：`analysis.analyzer-timeout-ms` | **未消费**：RealtimeConsumer 仅逐域异常隔离，没有超时机制 |
| ReportConfig：`report.minute.retention-days`、`report.hour.retention-days`、`report.long-term.retention-months` | 每日清理时直接读，交给既有删除接口；JdbcReportBucketSink 发 ALTER DELETE，不发 MODIFY TTL；固定表 TTL 仍可能先行删除数据，不能宣称加大配置延长物理留存 |
| `report.minute-flush-delay-seconds` | 每秒检查，计算最新可刷的已完成分钟；成功后记已刷分钟，失败可重试；延迟增大不能倒退重复刷旧分钟 |
| `report.exact-values.max`、`report.distribution-buckets` | **未消费**：DurationDistribution 当前固定默认精确上限 200、分箱 16；没有热重建历史分布或表结构 |
| `report.bucket-cache-seconds` | **未消费**：没有对应卡片序列缓存，不制造一个只有字段更新的假缓存刷新 |
| MetricConfig：`metric.top-n` | 记录时新序列晋升及小时初次固化直接读；不撤销既有晋升，缩减在尚未固化小时的首次固化时应用；定稿后归属不变，重复 finalize 不重排 |
| TraceConfig：`trace.retention-days` | TraceController 和 SamplePort 每次请求直接读，影响可下钻/过期判断；不修改 nc_raw_tree 的固定 TTL |
| `trace.sample-rows` | ReportController 每次请求默认 limit 直接读；显式请求 limit 优先 |
| `trace.sample-rate` | **未消费**：原始树采样逻辑尚未实现 |
| AlertConfig：`alert.evaluate-delay-seconds` | AlertController 预览和 AlertEvaluationJob 每次直接读；定时任务一分钟只推进一次，增大延迟不重判旧分钟 |
| `alert.notify-timeout-ms`、`alert.dedup-per-minute` | **未消费**：外部投递仍不支持，现有判定去重固定开启；字段刷新不改变这些限制 |
| QueryConfig：`query.max-buckets`、`query.max-instances-topn` | **未消费**：未新增查询点数限制或机器 Top N 截断 |
| HeartbeatConfig：`heartbeat.topn` | **未消费**：保留现有各 JVM 独立曲线，不加默认机器截断 |

server.port、MySQL/ClickHouse 连接、MyBatis、平台初始时区是启动参数，不假称可热重建资源。MySQL 平台业务数据和 SDK ClientConfig 不纳入这 28 个动态键。

## 测试与构建

- 两端独立 POM，无 parent，根 `pom.xml` 删除，无新 common-parent 或两端工件依赖。坐标仍为 `com.neocat:neocat-backend/neocat-client-java:0.1.0-SNAPSHOT`。
- 保留 Java 17、UTF-8、compiler 3.13.0 `-parameters`、Lombok 1.18.46、Boot 3.3.13、Modulith 1.2.10、Spock 2.3-groovy-4.0、Groovy 4.0.27、JUnit 5.10.5 版本管理、Surefire 3.2.5、protobuf 3.25.5。SDK 不引入 Boot 或 Apollo，编译依赖只有 protobuf。
- `StaticConfigFixture` 仅在测试源码；Spock 全局扩展在测试实例初始化前保存静态值、设置测试值，清理后恢复（初始化失败也恢复）；`SpockConfig.groovy` 关闭并行运行。
- `RuntimeConfigSpec` 保留原规格名用于 PRD 追溯，但不再测试旧接口：验证 28 键全集、真实处理器初始绑定和变更。
- `ApolloStaticWiringSpec` 使用真实原生注解注册链路与 common-apollo 自动配置，只替换外部 ConfigManager；验证初始化顺序、监听回调、同一排名对象更新后行为以及在线值优先于缓存源。
- `DynamicConfigConsumerSpec`、`DynamicHttpConfigSpec`、`RealtimeConsumerLoopSpec`、`DynamicReportScheduleSpec` 覆盖同一对象变化、动态队列/线程/批参数、幂等/迟到/Top N/Trace/Alert/调度边界。

在仓库根分别执行：

```bash
mvn -f backend/pom.xml clean verify
mvn -f client-java/pom.xml clean verify
node scripts/check-contract-alignment.mjs
node scripts/check-error-codes.mjs
node scripts/check-protocol-drift.mjs
mvn -f backend/pom.xml -DskipTests package org.springframework.boot:spring-boot-maven-plugin:3.3.13:repackage
```

可执行 Boot jar 仍需显式 repackage；不是普通 package 隐式产物。有效脚本、部署/联调和技术手册已改用独立构建路径；旧方案中历史验收命令不是当前运行指引。

### 本地验收结果

- 根 POM 删除后，两端 `clean verify` 分别通过：backend **100 套件 / 1272 项**，client-java **2 套件 / 30 项**，失败/错误/跳过均为 0。
- effective-pom 检查两端无 parent，产物坐标不变，compiler 均保留 `<parameters>true</parameters>`；`javap -v` 核对 UserAdminController.UserDraft 的 MethodParameters 含 username/password。
- SDK dependency tree 无 Spring/Apollo，JUnit Platform 保留原管理版本 1.10.5，Groovy 4.0.27；SDK jar 无 Spring/Apollo 类。
- 64 个后端路径覆盖 39 个前端 mock 路径，44 个错误码对齐，19 个两端协议生成类逐字节相同。
- 显式 repackage 成功，manifest 含 JarLauncher、NeoCatApplication、Boot 3.3.13；BOOT-INF/classes 与依赖库存在，common-apollo 已入包。
- 自查修正了重复 finalize 重排定稿小时、延迟增大导致任务倒退重复处理，以及 SDK 拆 POM 后传递测试依赖版本管理丢失；对应离线回归已绿。

## 未现场验收

不启动真实 Apollo/MySQL/ClickHouse；内存 Apollo 变更不等于现场推送成功，SQL 删除/固定 TTL 交互、生产 Spring 启动和在线上报仍须按联调手册验收。未执行数据库迁移、未补 SDK HTTP sender/鉴权或外部通知投递。目录不是 Git 仓库，未提交或推送。
