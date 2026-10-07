# NeoCat 第一期详细功能设计（V2）

> **状态：当前有效（CANONICAL）**  
> **版本：V2.0**  
> **日期：2026-09-24**

本目录是 NeoCat 第一期产品设计的唯一有效来源。它替代：

- `docs/superpowers/specs/2026-09-21-neocat-product-design.md`
- `docs/superpowers/specs/2026-09-21-neocat-implementation-plan.md`

上述旧文件仍保留，但已经标记为 `SUPERSEDED`，不能作为新的产品或实现依据。

总纲当前明确了 **32 条端到端业务流程链路**，分域文档分别给出每条链路的前置条件、主流程、状态变化、权限、异常行为和验收标准。

## 文档结构

| 文档 | 内容 |
|---|---|
| [00-product-design.md](./00-product-design.md) | 产品总纲、共享概念、统一时间/指标语义、角色权限、完整业务流程矩阵、修订记录、一期验收总表 |
| [01-identity-organization.md](./01-identity-organization.md) | 初始化、账号、登录会话、组织树、成员继承、组织权限 |
| [02-ingest-trace.md](./02-ingest-trace.md) | 上报契约语义、MessageTree、Trace、自动发现、幂等、迟到、过载与数据质量 |
| [03-reports-time-buckets.md](./03-reports-time-buckets.md) | 时间范围、时间桶、QPS、Transaction、Event、Problem、Heartbeat、取样与图表行为 |
| [04-metric-dependency.md](./04-metric-dependency.md) | Metric 标签序列、Top 1000/other、分位、依赖报表、跨服务缺失语义 |
| [05-dashboards.md](./05-dashboards.md) | 叶子组织大盘、卡片、统计项聚合、四则运算、维度下钻、阈值线 |
| [06-alerts-notifications.md](./06-alerts-notifications.md) | 服务告警、组织告警、预告警、启停、滑动窗口、通知与规则失效 |

## 阅读规则

1. 总纲定义跨域术语和优先级；分域文档不得重新定义共享概念。
2. 分域文档中的“必须”是一期产品行为；“不做”是一期明确排除项。
3. 当前设计只描述产品行为、用户可见结果和性能/数据承诺，不规定数据库、类、接口或具体技术选型。
4. 当现有 `front/`、`ui/` 或旧实现计划与本目录冲突时，应先修正产品实现，而不是反向修改本目录以迁就原型。
5. 本目录中的修订项覆盖旧产品规格；修订项在总纲第 12 节集中列出。

## 一期明确不做

- 原版 CAT 客户端与原版上报协议兼容；
- SSO、LDAP、OAuth；
- 用户自助注册；
- 个人大盘；
- 非叶子组织挂大盘；
- 服务绑定组织；
- 数据模型中的环境字段；
- 非 JVM Heartbeat；
- 按服务覆盖慢阈值；
- 告警恢复通知、静默窗口、智能异常检测；
- 告警确认、P1/P2 严重度、告警事件历史页；
- 除站内预告警、邮件、钉钉、飞书以外的通知通道；
- 运行期间修改平台时区；
- 产品内操作审计查询；
- 全局大盘、应用总览、Business 漏斗、问题单工作流、CPU/Load Heartbeat 扩展。
