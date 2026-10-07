# 02｜上报、自动发现与 Trace

## 1. 范围

本域定义任意语言上报方如何表达一次本地 MessageTree、平台如何接收/校验/去重/过载处理，以及如何把多棵本地树组装为跨服务 Trace。

一期使用 NeoCat 自己公布的上报约定，不兼容原版 CAT 协议。具体传输格式可由实现阶段定义，但不得改变本文件的产品语义。

## 2. MessageTree 语义

一个 MessageTree 表示一个服务进程内、一次请求上下文中的本地调用树：

```text
order 服务 MessageTree
├── Transaction: POST /orders
├── Transaction: SQL/insert_order
└── RemoteCall: pay
```

跨服务调用时，每个服务产生自己的 MessageTree：

```text
Trace root
├── gateway MessageTree
├── order MessageTree
└── pay MessageTree
```

Trace 不是一次 HTTP 上报的对象，而是平台依据追踪 ID 将多个本地树拼成的查询对象。

## 3. 必要上报语义

每棵本地树必须能识别：

- `serviceName`；
- `instanceId`；
- 全局唯一 `messageId`；
- `rootMessageId`；
- `parentMessageId`；
- 树内节点 `nodeId`；
- 节点类型、名称、状态、事件发生时间；
- Transaction 耗时；
- Metric 数值和标签；
- JVM Heartbeat 的堆、GC、线程数据（如为 JVM 服务）。

平台不要求所有服务都上报所有模型。非 JVM 服务没有 Heartbeat 不影响其他报表。

## 4. 接收流程

```text
收到上报请求
→ 校验协议版本、必填字段、大小、节点数量和时间
→ 校验 serviceName、instanceId、messageId
→ 检查幂等
→ 检查事件时间是否在可接收迟到范围
→ 合法身份立即更新服务/实例目录
→ 尝试写入有界队列
→ 返回快速接收结果
```

请求接收成功只表示平台接受了处理尝试，不表示报表已经完成。报表按分钟刷新。

## 5. 自动发现

合法身份校验通过后立即执行：

```text
serviceName 首次出现 → 创建服务
instanceId 首次出现在服务下 → 创建实例
```

即使之后队列已满导致整棵树丢弃，服务和实例仍然可以在目录中被发现，但在当前报表范围没有数据时不显示。

服务/实例列表显示规则：

- 当前 Transaction 页面按当前时间范围内有 Transaction 数据过滤；
- Event、Problem、Heartbeat、Metric、依赖页面按各自数据类型过滤；
- 历史时间范围查询可以重新显示历史上有数据的服务/实例；
- 不做永久空壳实例展示、手工删除或归档状态。

## 6. 幂等规则

### 6.1 MessageTree 级幂等

`messageId` 在整套 NeoCat 部署内必须全局唯一，作为一棵 MessageTree 的幂等键。

| 情况 | 行为 |
|---|---|
| 未见过 messageId | 接收并处理 |
| 已见过且内容相同 | 幂等成功；不重复目录、统计、Trace |
| 已见过但内容不同 | 拒绝；记录 ID 冲突质量异常 |

### 6.2 节点与跨服务关系

- `nodeId` 只在本地树内定位节点；
- `rootMessageId` 用于聚合同一 Trace；
- `parentMessageId → messageId` 建立树级父子关系；
- 同一 Trace 下允许多棵来自不同服务/实例的 MessageTree；
- 不能仅以 `rootMessageId` 去重整条 Trace。

## 7. 事件时间与迟到

报表时间桶使用节点事件发生时间，不使用服务端接收时间，也不把整棵树统一使用 root 时间。

允许接收：

- 当前平台时区自然小时；
- 刚结束的上一自然小时。

更早的 MessageTree：

- 整棵拒绝；
- 不进入任何报表、依赖或 Trace；
- 记录数据质量异常；
- 不触发历史回补。

允许范围内的迟到数据可以更新报表数值，但不重新判定已经完成的告警分钟点，也不补发历史通知。

## 8. 有界队列与业务隔离

```text
入队
├─ 有空间：进入后台消费
└─ 已满：丢弃监控数据，业务快速继续
```

要求：

- 接收端不得等待分析器处理完成；
- 队列满不阻塞业务调用；
- 丢弃数据不补算；
- 记录接收量、丢弃量和质量状态；
- 不因一个下游分析器变慢而阻塞其他分析器。

## 9. RealtimeConsumer 扇出

后台消费者取出一棵 MessageTree 后，向以下处理域扇出：

1. Transaction；
2. Event；
3. Problem；
4. Heartbeat；
5. Metric；
6. Dependency；
7. Trace Store。

一个处理域失败时：

- 其他处理域继续处理同一树；
- 记录失败域、MessageTree ID 和原因；
- 当前域可能出现缺口，其他域不应一起丢失。

## 10. Trace 组装

用户从报表取样进入 Trace 时：

```text
读取取样对应的本地 MessageTree
→ 按 rootMessageId 找同一 Trace 的其他树
→ 按 parentMessageId 连接 MessageTree
→ 展开各树内部节点
→ 输出跨服务调用树
```

缺失规则：

- 已知父子关系但子树未收到：显示缺失节点；
- 子树已过期：显示已过期缺失；
- 只有部分树可用时，其余树照常展示；
- Trace 组装失败不影响已经完成的报表统计。

## 11. 过载、重复和异常验收

1. 相同 MessageTree 重试不会增加总量、失败量、QPS 或取样数量。
2. 相同 ID 不同内容会被拒绝并有质量异常记录。
3. 队列满时业务请求快速返回，平台不等待消费。
4. 过期树整棵拒绝，不出现“只进部分分析域”的结果。
5. 单个 Analyzer 异常不阻断其他 Analyzer。
6. 跨服务只收到部分本地树时，Trace 有明确缺失节点。
7. 7 天后汇总仍可查，原始树不可打开。
