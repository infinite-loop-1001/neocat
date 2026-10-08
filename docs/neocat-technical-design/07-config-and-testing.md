# 07｜Apollo 配置、测试策略与前端 Mock 开关

## 1. Apollo 配置

### 1.1 接入方式

| 项 | 值 |
|---|---|
| AppId | `neocat` |
| Namespace | `application`；如需拆分，部署时同步配置引导命名空间 |
| 本地兜底 | 无。Apollo 客户端负责引导与远端读取；启动后由 `ApolloConfigGuard` 校验必需键，缺失或非法即拒绝启动 |
| 语言 | Java；按用途拆分的 `@Configuration(proxyBeanMethods=false)`，`@ApolloStaticValue` 注入 `public static volatile` 字段 |
| 格式 | `.properties`（不使用 yml） |
| 热更新 | common-apollo 注册 Apollo 监听器，业务直接读取静态字段；实际消费边界与未消费键见 [迁移状态](12-apollo-static-config-status.md)，不把字段刷新等同于所有资源热切换 |

### 1.2 配置项全集

| 键 | 默认 | 作用 | 是否热更新 |
|---|---|---|---|
| `neocat.ingest.queue.capacity` | `65536` | 有界队列容量；过载阈值 | ✓（入队直接读；缩容保留在途数据） |
| `neocat.ingest.consumer-threads` | `4` | 消费者线程数 | ✓ |
| `neocat.ingest.batch-size` | `200` | 单次 drain 的树数 | ✓ |
| `neocat.ingest.batch-timeout-ms` | `200` | drain 等待上限 | ✓ |
| `neocat.ingest.max-trees-per-batch` | `200` | 单次 HTTP 批次树数上限 | ✓ |
| `neocat.ingest.max-batch-bytes` | `1048576` | 单次 HTTP 批次字节上限 | ✓ |
| `neocat.ingest.max-nodes-per-tree` | `3000` | 单树节点上限 | ✓ |
| `neocat.ingest.idempotency-window-minutes` | `120` | 内存幂等窗口 TTL | ✓（新条目；不重写已有过期时间） |
| `neocat.ingest.auth-token` | 空 | 预留共享令牌 `X-NC-Token` | 字段刷新；未接入鉴权 |
| `neocat.ingest.accept-late-hours` | `2` | 可接收的自然小时数（当前 + 上一） | ✓ |
| `neocat.analysis.analyzer-timeout-ms` | `5000` | 预留单树单域分析超时 | 字段刷新；未实现超时隔离 |
| `neocat.analysis.shard-count` | `16` | 当前小时报表分片数 | ✗（需重启） |
| `neocat.report.exact-values.max` | `200` | 预留精确值限制；当前分布实现固定 200 | 字段刷新；未消费 |
| `neocat.report.distribution-buckets` | `16` | 预留分布段数；当前实现固定 16 | 字段刷新；未消费；表结构不热改 |
| `neocat.report.minute.retention-days` | `30` | 分钟桶留存 | ✓（下次每日清理读新值；不改表 TTL） |
| `neocat.report.hour.retention-days` | `30` | 小时桶留存 | ✓（下次每日清理读新值；不改表 TTL） |
| `neocat.report.long-term.retention-months` | `13` | 日/周/月桶留存 | ✓（下次每日清理读新值；不改表 TTL） |
| `neocat.report.minute-flush-delay-seconds` | `5` | 分钟刷库延迟（每分钟 :05） | ✓ |
| `neocat.report.bucket-cache-seconds` | `60` | 预留卡片序列缓存 | 字段刷新；未实现缓存 |
| `neocat.metric.top-n` | `1000` | Metric 每小时保留的真实序列数 | ✓ |
| `neocat.trace.retention-days` | `7` | Trace/取样「过期」判定边界 | ✓（不修改固定表 TTL） |
| `neocat.trace.sample-rate` | `1.0` | 预留原始树采样率（0–1） | 字段刷新；未实现采样 |
| `neocat.trace.sample-rows` | `30` | 每行取样条数 | ✓ |
| `neocat.alert.evaluate-delay-seconds` | `5` | 完整分钟点后延迟判定 | ✓ |
| `neocat.alert.notify-timeout-ms` | `3000` | 预留通道发送超时 | 字段刷新；未消费 |
| `neocat.alert.dedup-per-minute` | `true` | 预留同一分钟去重开关 | 字段刷新；当前去重固定启用 |
| `neocat.query.max-buckets` | `2000` | 预留单次查询返回点数上限 | 字段刷新；未消费 |
| `neocat.query.max-instances-topn` | `20` | 预留机器视图默认 Top N | 字段刷新；未消费 |
| `neocat.heartbeat.topn` | `10` | 预留 Heartbeat 默认 Top N | 字段刷新；未消费（保持各 JVM 独立曲线） |

### 1.3 不进 Apollo 的值（存 MySQL，页面可改）

平台时区（只读）、慢 URL/SQL/调用/缓存阈值、通知通道开关与凭据、账号/组织/大盘/告警规则。原因：这些是**业务数据**，需要审计与事务一致性，且 PRD 要求它们在产品页面可管理。

### 1.4 Apollo 不可用时的行为

```text
启动：先从 Apollo 配置服务在线读取命名空间，远端连接失败（包括本机有缓存）或缺少必需值 → 启动失败
运行：Apollo 客户端监听变更，common-apollo 更新静态字段；转换失败保留旧值并记录错误
```

单测使用替身或在测试入口注入属性；在线引导检查用受控 HTTP 替身，**不连真实 Apollo**。

---

## 2. Spock 测试策略

### 2.1 分层与边界

| 层 | 被测对象 | Mock 掉什么 | 用真实什么 |
|---|---|---|---|
| 领域单测 | 领域对象与规则（`domain` 包） | 无关依赖 | 纯内存对象 |
| 应用服务单测 | `app` 用例（`@ApplicationModuleTest`） | Mapper、ClickHouse DAO、Apollo、通道、时钟、队列 | 模块内领域逻辑 |
| 事件契约单测 | 发布/订阅 | 订阅者 | 事件载荷 |
| 跨模块契约单测 | 模块 `api` 接口 | 被调用模块实现 | 调用方逻辑 |
| SDK 单测 | `client-java` | `MessageSender`（真实实现为 HTTP，单测用假实现） | 队列、批处理、错误吞掉 |

### 2.2 纪律（硬约束）

1. **只跑单测**：`mvn -f backend/pom.xml test` 与 `mvn -f client-java/pom.xml test` 在无中间件环境下必须全绿；已缓存构建依赖可加 `-o`。
   两者都不依赖任何协议模块先安装 —— 协议类由各模块从仓库根 `proto/` 自行生成（04 §1.1）。
2. **禁止**启动或连接 MySQL、ClickHouse、Apollo、Redis、SMTP、钉钉、飞书。
3. **禁止** H2 / Testcontainers 冒充中间件。
4. Mapper 与 DAO 的**连通性留待人工**：提供 `infra` 实现但不写集成测；`@Tag("integration")` 标记，默认不执行。
5. 后端时间统一经公共静态 `TimeProvider.now()` / `millis()` 获取，不通过 Spring 注入 Clock，TimeProvider 不提供设置时钟的方法，禁止在领域代码里 `Instant.now()`。Groovy 单测直接给 `TimeProvider` 的私有静态时钟字段赋值，结束后在 cleanup / finally 中赋回 `Clock.systemUTC()`；不做线程隔离、嵌套或作用域恢复，所有线程共享时钟，修改时钟的测试串行运行。
6. 动态配置只在使用点直接读静态字段；测试用 `StaticConfigFixture` 设置小值，每个 Spock 用例由扩展保存/恢复静态字段，禁止并行运行共享静态值的规格。不使用生产离线默认值工厂。
7. 外部通道实现 `NotificationSender` 接口，单测断言「调用了几次、参数是什么」，不判真实投递。
8. **领域对象不得依赖框架注解**：曾用 `record` 的不可变类型现为普通类 + Lombok（`@Getter`/`@EqualsAndHashCode`/`@ToString` + 显式全参构造器）。这是**编译期**依赖，不是运行期框架依赖，符合本项目「领域类是 POJO」的纪律。
9. **`@RequestBody` 绑定依赖编译期 `-parameters`**：普通类不像 record 那样隐式携带构造器参数名，JSON 字段绑定需 `maven-compiler-plugin` 的 `<parameters>true</parameters>`。两端独立 POM 均显式保留；删除会导致请求体字段静默变 `null`，必须同时核对编译产物并做真实 HTTP 联调（见 `09-integration-checklist.md`）。

### 2.3 关键场景清单（每个模块至少覆盖）

| 模块 | 必测场景 |
|---|---|
| identity | 密码不足 8 位；管理员不能授予 ADMIN；超管不能改自己；重置吊销全部会话；禁用吊销会话；首登只允许改密；滑动续期；登出只注销当前会话；登录失败不区分账号不存在 |
| organization | 同父名称唯一；叶子+资源时新增子节点被拒；非叶子删除被拒；删除需名称匹配；级联删除；祖先继承；移除后即时撤销；多路径不去重误删 |
| platform | 仅在未初始化时可初始化；时区只读；慢阈值变更只影响后续；通道未配置不可选 |
| catalog | 首次上报即发现；队列满仍已发现（顺序不变式）；按类型+范围过滤不返回空壳 |
| ingest | 版本/字段/大小校验；过期整棵拒绝；幂等同内容 → DUPLICATE；同 ID 异内容 → ID_CONFLICT；队列满 → DROPPED 且不阻塞；发现先于入队 |
| analysis | 7 路扇出；单域异常不阻断；Metric Top1000 与 other；跨小时缺口；分布合并而非平均分位；Problem 五类派生与可重叠 |
| trace | 按 root 聚合；缺失节点；过期节点；部分可用；过期不阻断报表 |
| query | 全部时间范围与粒度；部分覆盖桶；缺数≠0；QPS 分母三态；环比桶序号对齐；取样 30 条倒序；分位合并 |
| dashboard | 非叶子拒绝；单位校验（+/- 兼容、*/推导、除零不可计算）；缺数入缺；公式变更通知告警并使规则关闭清零；下钻不改默认聚合 |
| alert | 保存即关闭；启用后只从启用点建窗；X=3 第 3 点触发；持续满足每分钟触发；缺数打断；AND/OR；编辑自动关闭；收件人移除；无人仍评估不发送；补人重建窗口；卡片删除致规则失效保留 |
| client-java | 队列满丢弃计数；发送异常不抛出；批量 flush；shutdown 尽力；message_id 唯一；协议生成类不含任何 Spring 类型 |

### 2.4 覆盖率目标

| 指标 | 目标 |
|---|---|
| 模块 `domain` + `app` 包行覆盖 | ≥ 85% |
| 模块 `api` 契约 | 100%（每接口至少 1 正 1 反） |
| PRD 32 链路 | 100% 有对应用例规格（链路→规格映射见 08 文档） |

---

## 3. 前端 Mock 开关

### 3.1 设计

```ts
// src/api/client.ts
import { mockHandler } from "@/mock/handler";

export const USE_MOCK = import.meta.env.VITE_USE_MOCK !== "false";   // 默认 true

export async function api<T>(path: string, init?: RequestInit): Promise<T> {
  if (USE_MOCK) return mockHandler<T>(path, init);                    // 单一分支
  const res = await fetch(`/api${path}`, { credentials: "include", ...init });
  if (!res.ok) throw new ApiError(await res.json());
  return res.json() as Promise<T>;
}
```

### 3.2 与现状的差异

| 现状 | 目标 |
|---|---|
| `src/api.ts` 自写假路由（275 行），**不发 fetch** | 删除；改为 `src/api/client.ts` 统一入口 |
| `src/mock/handlers.ts` MSW 只 18 个只读桩，且从未被命中 | 保留 MSW，按 03 文档接口全集实现，覆盖全部读写 |
| 两套 mock 并存互相不生效 | **唯一** mock 机制：`VITE_USE_MOCK=true` 走 MSW handler |
| 无开关 | `.env` / `.env.local` 控制；默认 mock |

### 3.3 开关语义

| `VITE_USE_MOCK` | 行为 |
|---|---|
| 未设置 / `true`（默认） | MSW 注册，`/api/**` 全部由 `src/mock/` 数据回答；**无需后端即可演示全流程**（成功标准 2 的验收状态） |
| `false` | 不注册 MSW，`fetch` 走 Vite proxy → `http://localhost:8080` |

### 3.4 Mock 数据要求

必须覆盖 PRD 流程，而非仅返回静态列表：

1. 登录（含首登改密的 `mustChangePassword` 分支）、登出、`/me`。
2. 账号：创建、重置密码、授予/取消 ADMIN、禁用/启用。
3. 组织：建节点、加/移成员、删除预览与二次确认、叶子资源约束。
4. 目录：随 `kind` 与 `range` 变化的服务/实例列表（含"无数据不展示"）。
5. 报表：Transaction/Event/Problem/Heartbeat/Metric/依赖 全域，**含缺口点**（`value: null`）与部分覆盖点。
6. 取样 30 条 → Trace（含缺失节点、过期分支）。
7. 大盘：叶子大盘、卡片增删改、公式单位校验失败示例、阈值线、机器下钻。
8. 告警：草稿预告警三态、保存即关闭、启用/关闭、编辑自动关闭、组织告警目标并集。

**Mock 的硬要求**：报表数据必须有 `null` 缺口与 `partial` 桶 —— 否则前端会把「缺数≠0」这条 PRD 口径做错。

### 3.5 动线自检

`npm run flow`（现有 `front/scripts/flow-check.mjs` 扩展）覆盖主链路可达性：

```text
登录 → 服务列表 → Transaction Type → Name → 趋势 → 取样 → Trace
登录 → 我的叶子大盘 → 建卡片 → 配公式 → 阈值线 → 一键创建组织告警 → 预告警 → 启用
管理员 → 账号管理 → 建号 → 重置 → 授管理员 → 禁用/启用
管理员 → 组织树 → 建节点 → 加成员 → 删除预览 → 删除
```

---

## 4. 本地运行

| 场景 | 命令 | 依赖 |
|---|---|---|
| 后端单测 | `./mvnw -pl backend test` | 无 |
| SDK 单测 | `./mvnw -pl client-java test` | 无 |
| 前端 mock 演示 | `cd front && npm run dev` | 无（默认 mock） |
| 前端连后端 | `VITE_USE_MOCK=false npm run dev` | 后端 + MySQL + ClickHouse |
| 全栈本地 | `docker compose up` + `./mvnw -pl backend spring-boot:run` | 本机无 docker 时由用户人工补齐 |

> 当前机器无 docker（已验证），因此**中间件连通性验证由你人工补齐 endpoint 后执行**，与后端「单测不连外部依赖」的约束一致。
