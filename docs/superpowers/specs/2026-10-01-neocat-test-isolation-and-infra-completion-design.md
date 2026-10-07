# NeoCat 单测隔离与生产适配补全设计

日期：2026-10-01

## 目标与边界

清理 `backend/src/test` 的所有手写 `Fake*` / `InMemory*` 外部依赖替身：测试一个领域服务、Controller 或任务时，其仓储、gateway、时钟、配置及外部客户端一律使用 Spock `Mock` / `Stub`，以返回数据和交互断言直接验证被测对象。不得把预设 mock 返回值当成仓储读写行为的证据。针对生产 `infra` 实现另写测试，生产实现作为被测对象，其依赖的 Mapper、查询客户端等使用 mock；真实中间件联调另行进行。删除只验证测试替身本身的测试，改由相应生产实现测试和领域服务测试承接真实规则。

本轮还要去掉现有七个生产类名中的 `MyBatis` 前缀，更新类名、构造器、Mapper 中的嵌套行类型、测试和文档引用；不改 Mapper 接口及 XML 的名称。生产 `infra/InMemory*` 是实际运行组件（当前小时聚合、排名、幂等），**不是测试 fake**：保留它们，并直接针对它们的逻辑编写测试。

保持现有 HTTP、鉴权、错误响应、Protobuf v1 契约和启动配置校验语义。没有真实 MySQL、ClickHouse、Apollo 的情况下不得宣称已完成生产联调。

## 生产端口与模块边界

在各端口的调用方 `infra` 实现端口；跨模块读取只能经被调用方 `api/internal` 中的窄契约，不能直接依赖对方 `domain`、Mapper 或存储实现。补充必要的内部 API 及 DTO，并验证 Spring Modulith 无环、Bean 唯一。当前已有 `ServiceAvailability` 和 `CatalogGateway` 的 lambda Bean 应计算为已有生产实现，不重复创建 Bean。

- `identity/infra`：`SessionRepository` 对接已有 `nc_session`，包含会话创建、多会话、按 ID 查找、30 分钟滑动续期、失效及账号级撤销；新增 SessionMapper/SQL，校验时间边界与更新并发。`common/time` 提供真实时钟 Bean。
- `organization/infra`：`MembershipRepository` 和 `EffectiveLeafRepository` 分别对接已有 `nc_org_member`、`nc_effective_leaf`；替换有效叶时事务内完成删除/写入，成员关系变更和权限重算的事务边界覆盖整次用例，防止权限半更新。
- 跨模块读取：`SeriesPresence` 从报表数据真实判断类型/范围是否有数据；`HistoricalFingerprintLookup` 从持久化原始树查询内容指纹；`SlowThresholdProvider` 从平台档案取得阈值；`QualityEventSink` 落到真实的质量统计链路；`SuperAdminProvisioner` 通过 identity 内部 API 创建首个超管；`OrgAccessGateway` 和 `RecipientGateway` 查询当前账号状态/有效叶权限；`CardInputSource` 和 `MinutePointSource` 使用查询模块的分钟/时间桶数据，必须区分缺数与零；`ChannelAvailability` 读取平台通道配置；`CardEventPublisher` 发出真实应用事件。为上述实现新增针对转换、异常、空数据、权限和时间边界的隔离单测。
- `OrgResourceGateway` 同时需要读/删除 dashboard 与 alert 资源；禁止形成 `organization ↔ dashboard/alert` 模块环。拟采用组织侧同步资源投影供拓扑判定，dashboard/alert 通过组织模块公开契约维护投影；删除预览与级联通过同步应用事件在同一事务中执行。此处改变资源更新边界，实施前需由用户确认；投影不一致时不能按“无资源”放行。补充事务及模块边界验证。
- `alert/infra`：`RecipientGateway`、`ChannelAvailability`、`MinutePointSource` 使用真实内部 API；`DeliveryLogger` 写运行日志且不暴露为产品历史。**仅 `Notifier` 的邮件、钉钉、飞书外部投递暂缓**：提供显式“未支持投递”的实现，调用时抛出可辨识错误，交由 `NotificationDispatcher` 既有逐通道异常处理记录失败，不报告成功、不生成伪造的投递记录。规则、收件人、权限、通道可用性和分钟点均不使用空实现。

现有接口中的 `noop()` / `denyAll()` / `empty()` 工厂不注册为上述生产 Bean；若其被用于隐藏缺失的必需端口，改为明确失败。已批准的 `Notifier` 投递例外须在文档和结果中持续标为未就绪。

## 测试迁移与验收

按领域服务测试、现有仓储重命名、缺失 SQL 仓储、跨模块读取/写入、告警非投递适配逐批推进。每批以端口清单核对测试中的替身使用与生产 Bean 提供者，并运行对应 Spock 测试；最后运行 `mvn -o clean test` 与模块边界、协议/HTTP 契约测试。使用 Mock/Stub 的测试只证明被测服务的决策和依赖调用；Mapper/SQL 的真实数据库语义以及 SMTP/Webhook 投递不由离线单测证明。

验收条件：测试目录不再存在手写 `Fake*` / `InMemory*` 外部依赖类或实例化；生产现有七个 `MyBatis*Repository` 类名消失；除明确例外的外部投递之外，缺失端口均有实际生产实现与独立隔离测试；上下文 Bean 缺失、循环依赖和 Apollo 离线启动均不会被空实现掩盖。真实中间件启动/联调没有环境时记录为未验证，而非视作通过。
