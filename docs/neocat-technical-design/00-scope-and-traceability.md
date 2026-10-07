# NeoCat 技术实现方案 · 阶段1 范围与追溯矩阵

> 状态：阶段1产物，待确认
> 输入：`docs/neocat-product-design-v2/`（CANONICAL）、`front/`（残次品，仅作现状参考）
> 已确认决策：允许 ClickHouse；上报 HTTP + Protobuf；方案落盘 `docs/neocat-technical-design/`；按模块组汇报；首次启动完成平台初始化；前端验收以 mock 开关打开为准；保留前端工程骨架、重写视图与 API 层。

## 1. 已确认的技术决策

| 项 | 决策 |
|---|---|
| 后端运行时 | JDK 17 + Spring Boot 3.3 + Spring Modulith（单进程，Modulith 模块 = 业务包） |
| 依赖管理 | `backend` 与 `client-java` 各自独立 Maven 项目，无根 POM；协议同源 `proto/` |
| 协议源 | 仓库根 `proto/neocat/ingest/v1/ingest.proto` 为唯一事实源；两端各自生成 Java 类，不设协议模块（见 04 §1.1） |
| ORM | MyBatis（仅用于 MySQL 配置与元数据） |
| 配置中心 | Apollo（技术参数：队列容量、批量大小、超时、保留期等）；平台业务值存 MySQL |
| 配置库 | MySQL（账号/组织/大盘/告警规则/慢阈值/通道/服务实例目录/平台区域） |
| 报表与原始树库 | ClickHouse（分钟桶、小时桶、日/周/月汇总、原始 MessageTree、Trace 关系） |
| 上报协议 | HTTP + Protobuf（`client-java` 作为官方 SDK；协议源在 `proto/`，见 04 §1.1） |
| 测试 | Spock 单测，mock 一切外部依赖，不连 MySQL/ClickHouse/Apollo/邮件/钉钉/飞书 |
| 前端 | Vue 3 + TS + Vite + ECharts；单一 API 客户端 + 全局开关（默认 mock） |

## 2. 技术指标目标值（建议值，已认可）

| 指标 | 目标 |
|---|---|
| 上报接收 P99 | < 50ms，队列满立即返回（不阻塞业务） |
| 上报规模 | 几百服务、每天十亿级调用，单服务单日支持 ≤ 20 亿次 |
| 数据进入 | 秒级进入处理链路（队列消费延迟 P95 < 1s） |
| 报表可查延迟 | ≤ 1 分钟（当前小时内存聚合每分钟落库） |
| 查询接口 P95 | < 1s（小时范围、单服务） |
| 告警判定 | 每个完整分钟点判定一次，判定延迟 < 30s |
| 留存 | 分钟/小时汇总 ≥ 30 天；日/周/月 ≥ 13 个月；原始 MessageTree 7 天 |

## 3. 现状盘点与处置

### 3.1 后端

| 项 | 现状 | 处置 |
|---|---|---|
| Maven POM | 原根 POM 声明两端，`backend/` 曾为空 | 现两端独立 POM、无父继承，根 POM 已删除（见 12） |
| `client-java` | 有 `NeoCat/Transaction/MessageSender/ClientConfig` + 1 个 Spock 规格，采用 `TX|domain|...` 文本协议 | 保留骨架，协议改为 Protobuf，重写规格（属上报域任务对） |
| 上报协议源 | 初版曾抽为独立 Maven 模块 `protocol` | **已撤销**：改为仓库根 `proto/` 单一 `.proto`，两端各自生成（04 §1.1） |

### 3.2 前端

| 现状文件 | 与 PRD 关系 | 处置 |
|---|---|---|
| `src/api.ts` | 自写的假路由函数（非 `fetch`），与 `mock/handlers.ts` 的 MSW 是两套并存、互不生效 | 重写为单一 API 客户端 + 全局开关 |
| `src/mock/handlers.ts` / `browser.ts` | MSW，仅 18 个只读桩 | 保留 MSW 方案，按新接口全集重做 |
| `views/OverviewView.vue`（应用总览） | **PRD 明确排除** | 删除 |
| `views/BusinessView.vue`（Business 漏斗） | **PRD 明确排除** | 删除 |
| `views/HostsView.vue` | PRD 无「Hosts」概念；Heartbeat 为 JVM 实例维度 | 改造为 Heartbeat 页 |
| `views/AlertsView.vue` | 含 P1/P2、ack、告警历史 —— 三项均**排除** | 重写为「规则列表 + 启停 + 预告警」 |
| `router.ts` `apps/:name/metrics → business`、`dependency → overview` | 重定向到被删页面 | 移除，指向真实 Metric / 依赖页 |
| `views/DashboardView.vue` | 是「全局大盘」（应用总数/全局 QPS），且卡片无统计项与公式 | 重写为叶子组织大盘 |
| `views/PlatformView.vue` | 时区可编辑 —— **PRD 不允许运行期修改** | 时区改只读，慢阈值与通道保留可编辑 |
| `components/MachinePicker` `ReportChart` `TimeScope` `TreeNode` | 结构可用 | 保留并按新接口调整 |
| `ShellView.vue` | 含「应用总览」「全局视图」导航 | 收敛导航为 PRD 能力集 |

## 4. 32 条业务流程链路 → 实现映射

域缩写：ID 身份会话、ORG 组织、CAT 服务目录、ING 上报、ANL 分析、TRC Trace、QRY 查询报表、DSH 大盘、ALT 告警、PLT 平台。

| # | 链路 | 域 | 后端模块 | 前端页面 | PRD 依据 |
|---:|---|---|---|---|---|
| 1 | 平台初始化 | PLT/ID | platform + identity | 首次启动引导 | 01 §2 |
| 2 | 创建账号 | ID | identity | 账号管理 | 01 §3.1 |
| 3 | 登录 | ID | identity | 登录 | 01 §4.1 |
| 4 | 重置密码 | ID | identity | 账号管理 | 01 §3.3 |
| 5 | 授予管理员 | ID | identity | 账号管理 | 01 §3.2 |
| 6 | 禁用/启用账号 | ID/ALT | identity + alert | 账号管理 | 01 §3.4 |
| 7 | 建组织树 | ORG | organization | 组织树 | 01 §5.1 |
| 8 | 管理成员 | ORG | organization | 组织树 | 01 §6 |
| 9 | 变更组织拓扑 | ORG | organization + dashboard | 组织树 | 01 §5.2 |
| 10 | 删除叶子 | ORG | organization（级联 dashboard） | 组织树 | 01 §5.3 |
| 11 | 本地上报 | ING/CAT | ingest + catalog | —（SDK） | 02 §2 |
| 12 | 上报校验 | ING | ingest | — | 02 §4 |
| 13 | 自动发现 | CAT | catalog | 服务列表 | 02 §5 |
| 14 | 上报幂等 | ING | ingest | — | 02 §6 |
| 15 | 过载丢弃 | ING | ingest | — | 02 §8 |
| 16 | 实时分析扇出 | ANL | analysis | — | 02 §9 |
| 17 | Transaction 诊断 | QRY/TRC | query + trace | 报表 Type→Name→趋势→取样→Trace | 03 §7 |
| 18 | Event 诊断 | QRY | query | 报表（Event） | 03 §8 |
| 19 | Problem 诊断 | QRY/ANL | analysis + query | Problem | 03 §9 |
| 20 | Heartbeat | QRY | query | Heartbeat | 03 §10 |
| 21 | Metric 诊断 | QRY/ANL | analysis + query | Metric | 04 §1–4 |
| 22 | 依赖诊断 | QRY/ANL | analysis + query + trace | 依赖 | 04 §6–8 |
| 23 | 时间滚动与留存 | PLT | platform（调度）+ query | 时间范围控件 | 03 §2, 00 §10 |
| 24 | 创建大盘 | DSH | dashboard | 叶子大盘 | 05 §2–4 |
| 25 | 编辑卡片 | DSH | dashboard | 卡片编辑 | 05 §4–7 |
| 26 | 创建服务告警 | ALT | alert | 告警配置 | 06 §7 |
| 27 | 创建组织告警 | ALT/DSH | alert + dashboard | 告警配置 | 06 §8, 05 §8 |
| 28 | 预告警 | ALT | alert | 告警配置（试算） | 06 §4 |
| 29 | 启用告警 | ALT | alert | 告警列表 | 06 §3.2 |
| 30 | 运行告警 | ALT | alert（分钟调度） | — | 06 §5 |
| 31 | 规则变更 | ALT/DSH | alert + dashboard | 卡片/告警编辑 | 06 §3.3, 05 §8 |
| 32 | 收件人变化 | ALT/ID/ORG | alert + identity + organization | 账号/组织管理 | 06 §9 |

覆盖检查：32/32，无 PRD 链路未被映射。

## 5. 明确不做（实现中必须不出现）

原版 CAT 客户端与协议兼容、SSO/LDAP/OAuth、用户自助注册、个人大盘、非叶子挂大盘、服务绑定组织、环境字段、非 JVM Heartbeat、按服务覆盖慢阈值、告警恢复通知/静默窗口/智能异常检测、告警确认与 P1/P2、告警事件历史页、站内预告警与邮件/钉钉/飞书以外的通道、运行期修改平台时区、产品内操作审计查询、全局大盘、应用总览、Business 漏斗、问题单工作流、CPU/Load Heartbeat。

已知违反项（阶段4 必须清理）：`OverviewView.vue`、`BusinessView.vue`、`HostsView.vue`、`AlertsView.vue` 的 P1/P2 与 ack 与历史、`PlatformView.vue` 的可编辑时区、`DashboardView.vue` 的全局大盘语义、路由中指向上述页面的重定向。

## 6. 跨域口径清单（实现中不可走样的语义）

1. **固定时区**：初始化设定，所有自然周期、环比、告警分钟点共用；运行期只读。
2. **QPS**：范围内总次数 ÷ 报表实际覆盖秒数；当前小时分母为整点至当前时刻；多机器先合并分子再除公共分母。
3. **时间桶**：左闭右开 `[bucketStart, bucketEnd)`；首尾部分桶按实际覆盖计算并标注。
4. **趋势默认**：桶内 Hits 总次数，不是 count/min 也不是默认 QPS。
5. **缺数据 ≠ 0**：缺数显示缺口并打断告警滑动窗口；确认无调用才显示 0，且耗时/比例类显示无值。
6. **分位合并**：桶内合并原始分布重算，不平均子桶分位。
7. **Trace 过期**：>7 天禁用下钻，汇总仍可查。
8. **告警窗口**：X 为整条规则的滑动窗口长度；保存即关闭；编辑即关闭；启用后只从启用后完整分钟点建窗。
9. **告警无历史**：正式触发不落站内历史，发送失败仅写运行日志。
10. **自动发现**：合法身份通过即建服务/实例目录，即使随后队列满被丢。
11. **幂等**：`messageId` 全局唯一；同 ID 同内容幂等，同 ID 异内容拒绝。
12. **迟到**：仅接受当前自然小时与上一自然小时；更早整棵拒绝且不回补。
13. **Metric Top1000**：每小时独立排名，其余并入 `other`；跨小时缺口不显示 other 值。
14. **依赖缺失**：下游树缺失不影响依赖边统计，Trace 缺失节点与之分开表达。
15. **大盘公式**：单位内部校验，除零不可计算，任一输入缺数则结果为缺口。
16. **组织权限**：直系 + 祖先继承，即时生效，管理员无旁路。

## 7. 阶段2 交付清单（预告）

1. 技术栈与技术指标说明；
2. 系统能力全景图 → 模块划分与交互 → 模块内部设计（含架构图/流程图/时序图）；
3. 前后端接口全集（含鉴权、错误码、分页、时间范围参数与桶语义）；
4. 上报接口契约（Protobuf schema、批次、幂等字段、迟到规则、响应码）；
5. MySQL DDL + 初始化脚本（含首次启动的时区/超管/默认慢阈值/空通道）；
6. ClickHouse DDL（分钟桶/小时桶/日月周汇总/原始树/Trace 关系/留存清理）；
7. Apollo 配置项清单与默认值；
8. Spock 测试分层策略与 mock 边界；
9. 模块级任务对（奇数=红单测，偶数=实现）清单。

## 8. 待你确认

1. 本矩阵与「不做」清单一节是否有需要增删的链路或边界？
2. 技术指标建议值（第 2 节）是否按此固定，或需要调整？
3. 阶段2 是否按第 7 节的九项交付？
