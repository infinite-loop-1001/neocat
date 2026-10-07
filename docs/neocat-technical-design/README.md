# NeoCat 技术实现方案

> 版本：v1.0 ｜ 状态：阶段2产物，待评审
> 上游依据：`docs/neocat-product-design-v2/`（CANONICAL，32 条链路）
> 范围与追溯：见 [00-scope-and-traceability.md](./00-scope-and-traceability.md)

## 文档地图

| 文档 | 内容 |
|---|---|
| [00-scope-and-traceability.md](./00-scope-and-traceability.md) | 范围、32 链路映射、不做清单、跨域口径 |
| [01-architecture.md](./01-architecture.md) | 技术栈、技术指标、系统能力全景、模块划分与交互、关键时序 |
| [02-modules.md](./02-modules.md) | 10 个模块的内部设计、对外接口、领域模型、算法 |
| [03-api-contract.md](./03-api-contract.md) | 前后端接口全集、统一参数/分页/错误码、鉴权 |
| [04-ingest-protocol.md](./04-ingest-protocol.md) | 上报协议（HTTP + Protobuf）、校验规则、幂等、迟到、过载 |
| [05-mysql-schema.sql](./05-mysql-schema.sql) | MySQL DDL + 初始化脚本（平台时区/超管/默认慢阈值/空通道；无外键与视图，引用完整性在应用层） |
| [06-clickhouse-schema.sql](./06-clickhouse-schema.sql) | ClickHouse DDL（分钟/小时/日周月桶、原始树、Trace 关系、依赖、质量） |
| [07-config-and-testing.md](./07-config-and-testing.md) | Apollo 配置项、Spock 测试分层与 mock 边界、前端 mock 开关 |
| [08-task-pairs.md](./08-task-pairs.md) | TDD 任务对（奇数=红单测，偶数=实现）、执行顺序与门禁 |

## 设计推进顺序（大到小）

```text
系统能力全景（01 §3）
  → 模块划分与模块间交互（01 §4–5）
    → 模块内部设计（02）
      → 接口契约与协议（03、04）
        → 存储与配置落地（05、06、07）
          → 执行编排（08）
```

## 一句话架构

单个 Spring Boot 3.3 进程（Spring Modulith，10 个业务模块）承载**接收 → 有界队列 → RealtimeConsumer 扇出 → 7 类分析器 → 内存当前小时报表 + ClickHouse 分钟桶 → 分钟/小时/日周月滚动**；MySQL 存配置与元数据，ClickHouse 存报表与原始树，Apollo 管运行参数，前端 Vue 3 通过全局开关在 mock 与真实后端之间切换。
