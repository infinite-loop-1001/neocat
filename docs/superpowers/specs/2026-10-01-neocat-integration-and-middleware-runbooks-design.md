# NeoCat 全流程联调手册与独立 Docker 中间件部署文档设计

## 目标与边界

产出两份可执行文档：一份覆盖 `00-scope-and-traceability.md` 所列 32 条业务链路的集成测试操作手册；一份面向异机中间件服务器的 MySQL、ClickHouse、Apollo 独立 Docker 容器部署/既有实例接入文档。应用机以 Spring Boot 后端 + Vite 前端真实后端模式运行，中间件在另一台机器。文档不是联调通过证明；所有真实启动、SQL 数据往返和投递均须现场取证。

## 文档结构

1. `docs/development/integration-test-runbook.md`：环境与基线记录 → Apollo/数据库/Bean 启动检查 → 初始化与账号组织 → Protobuf 上报与七域分析 → 全报表、时间桶、Trace → 大盘与公式 → 服务/组织告警 → 禁用/删除/并发与故障注入 → 前端真实模式 → 证据记录与清理。每个测试点有编号、操作、期望、证据和对应的 32 链路编号。由操作者记录实际结果，未执行统一标记为待验证，不把单测或 mock 演示算作生产联调。
2. `docs/development/middleware-docker-deployment.md`：网络与安全前置 → 既有 MySQL/Apollo 的连接与版本核查（优先路径）→ 独立 `docker run` 部署 ClickHouse → 可选新环境 MySQL 和 Apollo 各容器安装步骤 → 初始化新库/已有库增量迁移分支 → Apollo 发布完整必需配置 → 连通性、持久化、重启、备份与排障。不得使用 `docker compose` 一键启动；敏感信息放宿主机受限文件/交互输入，不写入仓库。

## 数据流与依赖

运行顺序为中间件核验和准备 → MySQL/ClickHouse schema → Apollo `application` 命名空间发布 → 应用机启动 Spring 并由 Apollo 客户端加载配置 → 前端 `VITE_USE_MOCK=false` → 通过 SDK/Protobuf 构造跨域样本 → UI/API/SQL/日志四类交叉证据。Apollo 发现返回的 Config Service 地址必须对应用机可达；MySQL JDBC 与 ClickHouse HTTP JDBC 地址须与应用机网络一致。配置键以 `ApolloConfigGuard` 的 `FRAMEWORK_KEYS` 与动态配置类上的 `@ApolloStaticValue` 注解，以及实际 DataSource/Mapper 定义为准；接口以当前 Controller/DTO 为准，旧文档示例需交叉核对，不直接照抄。

## 验收与异常处理

- 32/32 链路每条至少有明确用例，另覆盖正常/拒绝/超时或故障边界、权限、事务回滚、定时落库、平台时区、缺数与真实数据库往返。
- Apollo 不可达/必需键缺失必须导致启动失败；已有数据库不得直接重放全新建库脚本，先备份、制定增量迁移和数据核对（尤其 `nc_org_resource` 和告警 `channels`）。
- 邮件/钉钉/飞书真实投递暂缓：仅检查逐通道失败日志与失败不计成功，不声称外部送达；消息服务不列为必需容器。无可用真实中间件时记录待测，不伪造通过。
- 文档中的示例凭据均为占位符；端口只开放给应用机/管理机。对会破坏数据的测试使用隔离库/临时对象，并说明恢复步骤。

## 非目标

不在本次文档工作中修改生产实现、编写自动集成测试、执行真实服务器部署或承诺生产就绪；不把当前机器的单测结果替代现场验收。
