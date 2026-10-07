# 04｜上报协议（HTTP + Protobuf）

## 1. 传输层

| 项 | 约定 |
|---|---|
| 方法/路径 | `POST /api/v1/ingest` |
| Content-Type | `application/x-protobuf` |
| Content-Encoding | 支持 `gzip`（可选，建议 > 64KB 批次启用） |
| 协议版本 | 请求体内 `protocol_version`；当前唯一取值 `"1.0"` |
| 鉴权 | 一期为部署内网可信（`neocat.ingest.auth-token` 可选共享令牌，`X-NC-Token`） |
| 响应 | Protobuf `IngestResponse`；同时镜像 `X-NC-Status` 头便于网关观测 |
| 幂等 | 由 `message_id` 承担，与 HTTP 层无关 |
| 重试 | 客户端允许重试；服务端幂等保证不重复统计 |

**为什么 Protobuf 而不是 JSON**：日十亿级调用下，批次体积与解析开销是第一成本；`client-java` 可复用同一 `.proto` 生成类，避免两套序列化语义。

### 1.1 协议事实源的位置（决策：方案 B）

**协议事实源是仓库根的单一份 `proto/neocat/ingest/v1/ingest.proto`，不设独立 Maven 协议模块。**

| 项 | 决定 |
|---|---|
| `.proto` 位置 | `proto/`（仓库根，非 Maven 模块，无 `pom.xml`、无工件） |
| `backend` | `protobuf-maven-plugin` 的 `protoSourceRoot` 指向 `${project.basedir}/../proto`，本模块内生成 |
| `client-java` | 同上，指向同一份 `.proto`，本模块内生成 |
| 协议类的 Java 包 | 两端均为 `com.neocat.protocol.ingest.v1`（同一 `java_package`） |
| 服务端框架注解 | 仅存在于 `backend` 的 `com/neocat/protocol/ingest/v1/package-info.java` |

**为什么不设协议模块**：参照 `dianping/cat` —— CAT 的客户端 `lib/java` 自带一套消息模型，**不依赖** `cat-core`，客户端是零框架依赖的独立工件。若把协议抽成独立模块并让 SDK 依赖它，协议产物就会连带服务端关注点（本项目中曾出现 `spring-modulith-api` 混入协议 artifact）。

**为什么不「两端各存一份」**：CAT 的契约是文本/二进制线格式，漂移只能人工比对；而 Protobuf 的字段号一旦两侧不一致，**不报错、静默解析错位**，数据整体错乱。因此保留**单一 `.proto` 源文件**，两端各自从它生成。

**已知代价（需知情）**：`backend` 与 `client-java` 的工件各自包含一份 `com.neocat.protocol.ingest.v1.*` 类。二者是独立部署单元（服务端进程 / 业务方依赖的 SDK），不会同时进入同一类路径；但若有工程**同时**依赖 `neocat-backend` 与 `neocat-client-java`，将出现重复类。

**如何防止漂移**：两端生成物可逐字节比对——见 §2.1。

## 2. Protobuf Schema

Heartbeat 2026-10-02 扩展保留原字段号 1–5 并增加 scalar presence，新增 6–20 分项；
`presence_aware=true` 时所有字段仅按 presence 入桶，负数/未定义不上报。
旧客户端没有标记时，原 5 项省略的 scalar 继续按旧默认零解释，新增分项依旧缺失。
同一毫秒冲突采样用较大值稳定打破平局，跨毫秒始终按事件时间选择最后值。

```proto
syntax = "proto3";
package neocat.ingest.v1;
option java_package = "com.neocat.protocol.ingest.v1";
option java_multiple_files = true;

// ── 请求 ────────────────────────────────────────────────
message IngestRequest {
  string protocol_version = 1;        // "1.0"
  repeated MessageTree trees = 2;     // 单批次上限见 §4
}

message MessageTree {
  string service_name       = 1;      // 服务唯一标识；同名即同一服务
  string instance_id        = 2;      // 通常为 IP，不强制格式
  string message_id         = 3;      // 全局唯一；树级幂等键
  string root_message_id    = 4;      // 跨服务 Trace 根；无上游时等于自身
  string parent_message_id  = 5;      // 上游树；可为空
  int64  tree_timestamp     = 6;      // 树内最早节点事件时间，epoch millis
  repeated Node nodes       = 7;
}

message Node {
  string node_id            = 1;      // 树内唯一
  Kind   kind               = 2;
  string category           = 3;      // 见 §3.2
  string name               = 4;
  string status             = 5;      // "0" 成功；其他值（含 "ERROR"）非成功
  int64  timestamp          = 6;      // 事件时间，epoch millis（报表时间桶依据）
  int64  duration_ms        = 7;      // 仅 Transaction / RemoteCall
  string parent_node_id     = 8;      // 树内父节点；可空

  MetricValue metric         = 9;     // kind = METRIC
  Heartbeat   heartbeat      = 10;    // kind = HEARTBEAT（JVM）
  RemoteCall  remote_call    = 11;    // kind = REMOTE_CALL
  ExceptionInfo exception    = 12;    // kind = TRANSACTION 非成功时填写

  map<string, string> tags   = 13;    // 任意附加标签
}

enum Kind {
  UNKNOWN     = 0;
  TRANSACTION = 1;
  EVENT       = 2;
  HEARTBEAT   = 3;
  METRIC      = 4;
  REMOTE_CALL = 5;
}

message MetricValue {
  string name                = 1;     // 指标名
  double value               = 2;
  map<string, string> labels = 3;     // 标签键由上报方自定，平台不预注册
}

message Heartbeat {
  optional int64 heap_used_bytes = 1;
  optional int64 heap_max_bytes = 2;
  optional int64 gc_count = 3;
  optional int64 gc_time_ms = 4;
  optional int64 thread_count = 5;
  optional int64 young_used_bytes = 6;
  optional int64 young_committed_bytes = 7;
  optional int64 young_max_bytes = 8;
  optional int64 old_used_bytes = 9;
  optional int64 old_committed_bytes = 10;
  optional int64 old_max_bytes = 11;
  optional int64 metaspace_used_bytes = 12;
  optional int64 metaspace_committed_bytes = 13;
  optional int64 metaspace_max_bytes = 14;
  optional int64 young_gc_count = 15;
  optional int64 young_gc_time_ms = 16;
  optional int64 old_gc_count = 17;
  optional int64 old_gc_time_ms = 18;
  optional int64 full_gc_count = 19;
  optional int64 full_gc_time_ms = 20;
  bool presence_aware = 21;
}

message RemoteCall {
  string downstream_service = 1;      // 被调用服务名
  string downstream_address = 2;      // 可空
  string call_type          = 3;      // RPC / HTTP / MQ / ...
  string status             = 4;      // "0" 成功
}

message ExceptionInfo {
  string exception_name     = 1;      // Problem 异常聚合键
  string exception_message   = 2;     // 仅保留在取样与原始树
  string stack_trace          = 3;
}

// ── 响应 ────────────────────────────────────────────────
message IngestResponse {
  Status status              = 1;
  string code                = 2;     // 具体原因，见 §5
  int32  accepted_trees      = 3;
  int32  duplicate_trees     = 4;
  int32  dropped_trees       = 5;
  int32  rejected_trees      = 6;
  string message             = 7;
}

enum Status {
  ACCEPTED  = 0;   // 已接受处理尝试（不代表报表已完成）
  DUPLICATE = 1;   // 幂等命中
  DROPPED   = 2;   // 队列满，业务不阻塞
  REJECTED  = 3;   // 校验失败或过期
}
```

### 2.1 协议漂移的机械性证据（方案 B 的必要配套）

方案 B 让两端各自生成，安全性完全依赖「两端确实生成自同一份 `.proto`」。这一条**必须可执行**，不能只靠约定：

```bash
mvn -o -q -f backend/pom.xml -DskipTests package
mvn -o -q -f client-java/pom.xml -DskipTests package   # 两端各自生成
diff \
  backend/target/generated-sources/protobuf/java/com/neocat/protocol/ingest/v1/IngestRequest.java \
  client-java/target/generated-sources/protobuf/java/com/neocat/protocol/ingest/v1/IngestRequest.java \
  && echo "OK：两端协议类逐字节一致"
```

**判定**：`diff` 无输出即两端同源；一旦有人只改其中一侧的生成配置（或另存一份 `.proto`），此处立即报错。

## 3. 语义映射

### 3.1 树级

| 字段 | 平台用途 |
|---|---|
| `service_name` | 服务目录、报表主键前缀 |
| `instance_id` | 实例目录、机器维度 |
| `message_id` | 幂等键、取样定位、Trace 起点 |
| `root_message_id` | Trace 聚合键 |
| `parent_message_id` | 树级父子关系 |
| `tree_timestamp` | 迟到判定 |

### 3.2 节点 → 分析域映射

| `kind` | `category` 取值 | 进入的分析域 | 说明 |
|---|---|---|---|
| `TRANSACTION` | `URL` / `SQL` / `CALL` / `CACHE` | Transaction、Problem | `duration_ms` 参与分位；超慢阈值派生慢类 Problem |
| `EVENT` | 自定义（如 `business`） | Event | **无耗时**，不产出分位 |
| `HEARTBEAT` | `jvm` | Heartbeat | 按实例存，不合并 |
| `METRIC` | 自定义 | Metric | 标签规范化后排名 |
| `REMOTE_CALL` | RPC/HTTP/MQ… | Dependency、Trace | 无论下游是否上报都产生依赖边 |
| 任意 kind 且 `status != "0"` | — | Problem | 派生 `EXCEPTION`，聚合键 = `exception_name` |

**Problem 派生规则（链路 19）**

```text
onNode(node):
  if node.status != "0":
       emit Problem(EXCEPTION, key = exception_name)
       // 完整 exception_message 只进取样与原始树，不作聚合键
  if node.kind == TRANSACTION and node.category in {URL, SQL, CALL, CACHE}:
       threshold = platform.slowThresholds[category]
       if node.duration_ms > threshold:
            emit Problem(SLOW_<category>, key = node.name)
  // 同一次调用可同时属于 EXCEPTION 与 SLOW_*
```

### 3.3 时间桶归属

报表时间桶使用**节点事件发生时间**（`Node.timestamp`），不使用服务端接收时间，也不把整棵树统一用 `tree_timestamp`：

- Transaction/Event/Problem 桶 = 对应节点的 `timestamp`；
- Heartbeat/Metric 桶 = 该节点的 `timestamp`；
- `tree_timestamp` 只用于迟到判定与 Trace 过期计算。

## 4. 校验规则

| 校验 | 规则 | 越界结果 |
|---|---|---|
| 协议版本 | 必须为已知版本 | `REJECTED / UNSUPPORTED_VERSION`（400） |
| 批次树数 | ≤ `neocat.ingest.max-trees-per-batch`（默认 200） | 400 `BATCH_TOO_LARGE` |
| 批次字节 | ≤ `neocat.ingest.max-batch-bytes`（默认 1 MiB） | 400 `BATCH_TOO_LARGE`（60004） |
| 单树节点数 | ≤ `neocat.ingest.max-nodes-per-tree`（默认 3000） | 400 `TREE_TOO_LARGE` |
| 必填字段 | `service_name`、`instance_id`、`message_id` 非空；长度 ≤ 256 | 400 `MALFORMED_TREE` |
| `node_id` 唯一性 | 树内唯一 | 400 `MALFORMED_TREE` |
| 事件时间 | ∈ `[当前自然小时起点 − 2h, now + 1min]` | 422 `TREE_EXPIRED`（整棵拒绝） |
| 数值合法性 | `duration_ms ≥ 0`、`metric.value` 有限 | 400 `MALFORMED_TREE` |

**迟到边界（链路 12）**：可接收「当前平台时区自然小时」与「刚结束的上一自然小时」。更早整棵拒绝，**不进入任何报表/依赖/Trace**，只写数据质量事件，且不触发历史回补。

**整棵原子性**：任一节点校验失败 → 整棵树拒绝；同一棵树不会只进入部分分析域。

## 5. 响应码

Protobuf v1 的 `code` 字段仍为 `string`：异常编号写为十进制数字字符串；正常/重复/丢弃等非异常结果仍使用文字标识。名称与编号的权威定义在后端 `ErrorCode` 枚举中。

| HTTP | `status` | `code` | 客户端行为 |
|---|---|---|---|
| 202 | `ACCEPTED` | `OK` | 正常 |
| 202 | `DUPLICATE` | `DUPLICATE` | 视为成功，不重试 |
| 202 | `DROPPED` | `QUEUE_FULL` | 视为成功，**不重试**（不补算） |
| 400 | `REJECTED` | `60001`（UNSUPPORTED_VERSION）/ `60002`（MALFORMED_TREE）/ `60004`（BATCH_TOO_LARGE）/ `60003`（TREE_TOO_LARGE） | 修正后重试 |
| 409 | `REJECTED` | `60006`（ID_CONFLICT） | **不重试**，说明同 ID 内容漂移 |
| 422 | `REJECTED` | `60005`（TREE_EXPIRED） | **不重试**，说明时钟或积压异常 |
| 503 | `REJECTED` | `PLATFORM_INITIALIZING` | 退避重试 |

**语义边界**：`ACCEPTED` 只表示平台接受了处理尝试，**不表示报表已完成**。报表按分钟刷新（链路 11）。

## 6. 幂等（链路 14）

```text
fingerprint = sha256( 规范化序列化(MessageTree) )
// 规范化：字段按 tag 号排序；map 按键字典序；节点按 node_id 字典序；排除 float 精度噪声

决策表：
  未见过 message_id                    → 接收并处理
  已见过 且 fingerprint 相同            → DUPLICATE；不重复目录、统计、Trace、取样
  已见过 且 fingerprint 不同            → 409 ID_CONFLICT；写质量事件
```

查询路径：Caffeine 窗口（TTL 默认 120 分钟）优先；窗口外以 ClickHouse `nc_raw_tree` 存在性 + `fingerprint` 列兜底（7 天内精确）。

## 7. 过载（链路 15）

```text
offer(tree):
  成功 → ACCEPTED
  失败 → 计数 dropped++；写质量事件 QUEUE_FULL；返回 202 DROPPED
```

- 接收线程**永不阻塞**、**永不等**消费者；分析全部异步。
- 丢弃**不补算**、不回补历史。
- 记录：`nc_ingest_stat(分钟, accepted, duplicate, dropped, rejected, queue_watermark)`。
- 单分析域异常：写 `DOMAIN_FAILURE`，该域产生缺口，其他域不受影响（链路 16）。

## 8. 调用示例

### 8.1 HTTP（curl）

```bash
# 手工构造 1 棵树（二进制 proto 由 SDK 生成）
curl -sS -X POST http://localhost:8080/api/v1/ingest \
  -H 'Content-Type: application/x-protobuf' \
  -H 'X-NC-Token: dev-token' \
  --data-binary @tree.bin --output resp.bin -w '%{http_code}\n'
```

### 8.2 client-java（SDK）

```java
ClientConfig config = ClientConfig.builder()
        .endpoint("http://neocat.internal:8080/api/v1/ingest")
        .serviceName("order")
        .instanceId(localIp())
        .queueCapacity(10_000)
        .batchSize(100)
        .flushIntervalMillis(1000)
        .token(System.getenv("NC_TOKEN"))
        .build();

NeoCat cat = NeoCat.create(config);

Transaction tx = cat.newTransaction("URL", "POST /orders");
try {
    // 业务逻辑
    tx.setStatus(Transaction.SUCCESS);
} catch (Exception e) {
    tx.setStatus(Transaction.FAILURE);
    tx.setException(e);                 // → exception_name / message / stack
} finally {
    tx.complete();                      // duration 自动测量
}

cat.logEvent("business", "order-created", Event.SUCCESS);
cat.logMetric("order.amount", 128.5, Map.of("city", "上海", "channel", "app"));
cat.logJvmHeartbeat();                  // 显式 MXBean 采样；不启动后台定时器
cat.newRemoteCall("pay", "RPC", "POST /pay");   // → 依赖边
cat.shutdown();                         // 尽力 flush，超时不阻塞退出
```

SDK 契约（一期）：

| 项 | 要求 |
|---|---|
| 发送失败 | **不抛业务异常**，本地丢弃并计数 |
| 队列满 | 丢弃，`cat.dropped()` 可观测 |
| 刷新线程 | 单守护线程，批次 + 定时双触发 |
| 幂等 | `message_id` 由 SDK 生成（`service-instance-uuid`） |
| Trace 关联 | 业务需传入上游 `rootMessageId` / `parentMessageId`，SDK 提供 `cat.attachTrace(root, parent)` |
| 停机 | `shutdown(timeout)` 尽力发送，超时放弃 |

## 9. 上报侧验收（对应 PRD 02 §11）

| PRD 验收 | 实现点 |
|---|---|
| 相同树重试不增加任何统计与取样 | §6 幂等表 `DUPLICATE` 分支 |
| 同 ID 不同内容被拒绝并留质量异常 | §6 `ID_CONFLICT` + `nc_quality_event` |
| 队列满业务快速返回、平台不等消费 | §7 接收线程只 offer |
| 过期树整棵拒绝、不出现部分域 | §4 时间校验在入队前，整棵原子 |
| 单 Analyzer 异常不阻断其他 | §7 每域独立 try/catch |
| 部分本地树缺失时 Trace 有明确缺失节点 | 02 文档 §7 组装算法 |
| 7 天后汇总可查、原始树不可打开 | ClickHouse TTL 7d on `nc_raw_tree` |
