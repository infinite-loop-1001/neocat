# 03｜前后端接口契约

## 1. 通用约定

| 项 | 约定 |
|---|---|
| 前缀 | `/api` |
| 编码 | UTF-8，`application/json` |
| 鉴权 | `Cookie: NC_SESSION=<sessionId>`（HttpOnly、SameSite=Lax、Path=/） |
| 时间 | 请求用 `epoch millis` 或 `yyyy-MM-dd HH:mm` + 平台时区解释；响应用 epoch millis + `iso` 双写 |
| 时区 | 全部按 `GET /api/platform` 返回的平台时区解释；不随浏览器时区变化 |
| 桶 | 响应点必带 `bucketStart`、`bucketEnd`（左闭右开）、`partial`、`quality` |
| 分页 | `page`（0 起）、`size`（默认 20，上限 200）、响应 `total` |
| 会话续期 | 任何非白名单请求命中有效会话即续期 30 分钟 |

### 1.1 错误响应

```json
{ "code": 30003, "message": "该叶子组织已存在大盘或组织告警，不能新增子节点" }
```

`code` 是 JSON 数字；按业务域预留万位区间，名称仅用于开发文档和日志。完整编号和消息模板见 `backend/src/main/java/com/neocat/common/error/ErrorCode.java`；HTTP 状态显式映射见 `common/http/error/ErrorCodeMapping.java`，异常语义由子类表达。前端 mock 编号见 `front/src/api/error-codes.ts`。

| HTTP | code（名称/编号） | 场景 |
|---|---|---|
| 400 | `BAD_REQUEST`/10001、`INVALID_PARAM`/10002、`UNIT_MISMATCH`/40002、`FORMULA_INVALID`/40003 | 参数或公式非法 |
| 401 | `UNAUTHENTICATED`/20001、`BAD_CREDENTIALS`/20002 | 无有效会话或登录失败（不区分账号是否存在） |
| 403 | `FORBIDDEN`/20003、`NOT_ORG_MEMBER`/30004、`PASSWORD_CHANGE_REQUIRED`/20004 | 权限不足 |
| 404 | `NOT_FOUND`/10003、`ORG_NOT_FOUND`/30005、`DASHBOARD_NOT_FOUND`/40005 等 | 资源不存在 |
| 409 | `USER_EXISTS`/20005、`HAS_CHILDREN`/30001、`NAME_DUPLICATED`/30002、`ID_CONFLICT`/60006、`NOT_LEAF`/40001 | 冲突 |
| 410 | `TRACE_EXPIRED`/80001 | 原始树超 7 天 |
| 422 | `LEAF_HAS_RESOURCES`/30003、`TREE_EXPIRED`/60005 | 语义拒绝 |
| 500 | `INTERNAL_ERROR`/10004 | 未预期 |

### 1.2 会话与角色

- `USER`：可看所有服务报表、可配任意服务告警。
- `ADMIN`：USER + 账号/组织/平台配置。
- `SUPER_ADMIN`：ADMIN + 授予/取消 ADMIN。
- 组织大盘/组织告警：**仅叶子有效成员**（直系或祖先继承），管理员无旁路。

---

## 2. 身份与会话（identity）

| 方法 | 路径 | 权限 | 说明 |
|---|---|---|---|
| GET | `/api/platform/init-status` | 匿名 | `{ initialized: bool }` |
| POST | `/api/platform/initialize` | 匿名（仅未初始化时） | `{ timezone, adminUsername, adminPassword }` |
| POST | `/api/login` | 匿名 | `{ username, password }` → `{ user, mustChangePassword, entry }` |
| POST | `/api/logout` | 登录 | 仅注销当前会话 |
| GET | `/api/me` | 登录 | `{ id, username, role, mustChangePassword }` |
| POST | `/api/me/password` | 登录（含强制改密会话） | `{ oldPassword, newPassword }` |

**登录成功入口 `entry`**

```json
{ "type": "SERVICE_TRANSACTION", "service": "order", "kind": "TRANSACTION" }
{ "type": "SERVICE_LIST" }
```

规则：有最近访问服务且**在当前查询范围内有数据** → `SERVICE_TRANSACTION`；否则 `SERVICE_LIST`。
`mustChangePassword=true` 时只允许 `/api/me/password` 与 `/api/logout`。

### 2.1 账号管理

| 方法 | 路径 | 权限 | 说明 |
|---|---|---|---|
| GET | `/api/users` | ADMIN | 列表（含角色、状态、是否需改密） |
| POST | `/api/users` | ADMIN | `{ username, password }` → 创建 USER；密码 ≥ 8；用户名唯一 |
| POST | `/api/users/{id}/password/reset` | ADMIN | `{ password }`；置强制改密；吊销该账号全部会话 |
| POST | `/api/users/{id}/role` | SUPER_ADMIN | `{ role: "ADMIN" \| "USER" }` |
| POST | `/api/users/{id}/disable` | ADMIN | 吊销会话 + 移除告警收件人；保留组织成员关系 |
| POST | `/api/users/{id}/enable` | ADMIN | 恢复登录与组织成员继承；**不恢复**收件人 |

管理员不能授予 ADMIN（返回 `FORBIDDEN`）；超管不能修改自己（返回 `FORBIDDEN`）；一期不支持删除账号与改用户名。

---

## 3. 组织（organization）

| 方法 | 路径 | 权限 | 说明 |
|---|---|---|---|
| GET | `/api/orgs` | 登录 | 组织树（含 leaf 标记、成员数）；无组织权限者只看到管理入口为空 |
| GET | `/api/orgs/mine` | 登录 | 当前用户有权限的叶子列表（大盘入口） |
| POST | `/api/orgs` | ADMIN | `{ name, parentId \| null }` |
| POST | `/api/orgs/{id}/rename` | ADMIN | `{ name }` |
| POST | `/api/orgs/{id}/members` | ADMIN | `{ userId }` |
| DELETE | `/api/orgs/{id}/members/{userId}` | ADMIN | 移除成员 |
| GET | `/api/orgs/{id}/deletion-preview` | ADMIN | `{ orgName, dashboards:[{id,name,cardCount}], alertRuleCount, memberCount }` |
| DELETE | `/api/orgs/{id}` | ADMIN | `?confirmName=<组织名>`；不匹配 → `NAME_DUPLICATED` 语义拒绝 |

**约束**

- `POST /api/orgs` 于已有资源的叶子下新增子节点 → 422 `LEAF_HAS_RESOURCES`。
- 非叶子删除 → 409 `HAS_CHILDREN`。
- 删除叶子：原子级联删除大盘、卡片、组织告警。

---

## 4. 服务目录与报表（catalog + query）

### 4.1 目录

| 方法 | 路径 | 说明 |
|---|---|---|
| GET | `/api/services?kind=&range=&bucket=` | 仅返回**当前类型 + 当前时间范围有数据**的服务 |
| GET | `/api/services/{service}/instances?kind=&range=` | 同理的实例列表 |

### 4.2 报表统一参数

```text
kind      = TRANSACTION | EVENT | PROBLEM | METRIC | HEARTBEAT | DEPENDENCY
range     = HOUR:<epoch> | DAY:<yyyy-MM-dd> | WEEK:<yyyy-MM-dd> | MONTH:<yyyy-MM> |
            RECENT_1H | RECENT_3H | RECENT_6H | RECENT_12H | RECENT_24H | TODAY | THIS_WEEK
          也可用 from/to（epoch millis），系统按平台时区对齐桶
bucket    = 可省略；省略时按 range 的默认粒度
            只接受已定义的粒度档位：60 | 300 | 600 | 1200 | 3600 | 86400（秒）
            取值不在这组档位时**忽略该参数**，回落到 range 的默认粒度
stat      = HITS | FAILURES | FAILURE_RATE | QPS | AVG | MIN | MAX | TP50 | TP90 | TP95 | TP99 | TP999 | TP9999
mom       = DAY | WEEK | MONTH（环比，可选）
instances = 逗号分隔实例；缺省表示全部机器聚合；`__selected__` 表示用户勾选集合
```

**`range` 与 `bucket` 的职责边界**（实现口径，不可互换）：

```text
range  决定窗口（起点、终点、桶个数）
bucket 只覆盖粒度，不改变窗口

返回的每个点，其 bucketStart 都按 bucket（或 range 的默认粒度）在平台时区对齐。
调用方按桶起点与自己的桶序列逐一对齐，因此读取侧必须按请求粒度聚合：
粒度 ≠ 1 分钟时，源桶（分钟/小时）要先卷到目标桶再返回，
且卷的时候必须是「先合并分子与分布、再算分位」（§3 桶内聚合不变式）。
```

维度选择规则（读取侧据此挑源表，**与范围长度无关**）：

```text
粒度 ≥ 1 天        → 日桶
粒度 = 1 小时      → 小时桶
粒度 ≤ 20 分钟     → 分钟桶（并在读侧折叠到目标粒度）
```

> 为什么按粒度而不是按范围长度选源表：范围长不代表点要粗。
> 「3 天看每分钟」是合法请求；若按范围长度推断粒度，这类请求会拿到粒度不符的桶，
> 调用方按桶起点取数时只能命中少数边界桶，趋势图大面积缺失且不报错。

### 4.3 Transaction

| 方法 | 路径 | 说明 |
|---|---|---|
| GET | `/api/reports/transaction/types?service=&range=&instances=` | Type 汇总（含计数/耗时/分位/QPS） |
| GET | `/api/reports/transaction/names?service=&type=&range=&instances=` | 该 Type 下的 Name 列表 |
| GET | `/api/reports/transaction/series?service=&type=&name=&stat=&range=&bucket=&mom=&instances=` | Name 趋势 |
| GET | `/api/reports/transaction/machines?service=&type=&name=&stat=&range=&topN=` | 机器视图：`{ top:[...], other:{...}, all:[分页] }` |

### 4.4 Event

| 方法 | 路径 | 说明 |
|---|---|---|
| GET | `/api/reports/event/types?...` | 仅次数/失败/QPS，**无耗时字段** |
| GET | `/api/reports/event/names?...` | 同上 |
| GET | `/api/reports/event/series?service=&type=&name=&stat=&range=&bucket=&mom=&instances=` | 趋势；`stat` 只接受次数类与 QPS |

### 4.5 Problem

| 方法 | 路径 | 说明 |
|---|---|---|
| GET | `/api/reports/problem/categories?service=&range=` | 5 类：`EXCEPTION, SLOW_URL, SLOW_SQL, SLOW_CALL, SLOW_CACHE` |
| GET | `/api/reports/problem/names?service=&category=&range=&thresholdMs=` | 异常按异常名、慢类按 Transaction Name；`thresholdMs` 只筛已归类记录 |
| GET | `/api/reports/problem/series?service=&category=&name=&stat=&range=&bucket=&mom=` | 慢类支持分位；异常不支持分位（请求分位 → 400 `INVALID_PARAM`）。**后端当前未实现该路径**；前端 Problem 下钻改用通用 `/api/reports/series?kind=PROBLEM`，语义相同 |

### 4.6 Heartbeat（仅 JVM）

| 方法 | 路径 | 说明 |
|---|---|---|
| GET | `/api/reports/heartbeat/metrics?service=` | 20 项 JVM 指标，接口 key 与前端编目一致 |
| GET | `/api/reports/heartbeat/series?service=&metric=&range=&bucket=&topN=` | 按实例 Top N；**无 other 合并**；不合并不同 JVM 值 |
| GET | `/api/reports/heartbeat/instances?service=&metric=&range=&page=&size=` | 可搜索分页实例表 |
| — | — | 环比请求（`mom`）→ 400 `INVALID_PARAM`（一期不做） |

> 2026-10-02：真实协议、Java SDK、后端分析和查询已扩展到 20 项。原有堆 used/max、GC
> 总次数/耗时、线程数之外，增加 young/old/metaspace 的 used/committed/max 与
> young/old/full GC count/time。每实例每桶取事件时间最后一次有效采样，粗桶也是最后值，
> 不求和、不取最大值。GC 显示启动以来累计值，重启归零保留。旧历史没有最后值列时为空缺。
> SDK `logJvmHeartbeat()` 仅显式采样已识别 MXBean；不安装定时器，无法区分 Full GC 不上报，
> 可通过手动载荷显式提供；未定义值不补零。图表点分展示名与接口 key 仍是两套标识。

### 4.7 Metric

| 方法 | 路径 | 说明 |
|---|---|---|
| GET | `/api/reports/metric/list?service=&hour=` | 该小时排名序列（≤1000 真实 + `other`），含 `rank`、`reportCount` |
| GET | `/api/reports/metric/series?service=&metric=&labels=&stat=&range=&bucket=&mom=` | 具体组合趋势；跨小时被并入 other 的小时返回缺口 + `quality=MERGED_OTHER` |

#### Metric count 总览与详情（前后端共享契约，2026-10-02）

Metric 页面不从旧排名列表累加总量，使用以下独立契约。真实后端新增 `MetricCountController`，
当前小时查内存，历史查 ClickHouse；不会回退到硬编码指标或随机趋势。部署前需执行本次迁移。

| 方法 | 路径 | 响应（与现有报表一致，直接 JSON，无额外 data 包装） |
|---|---|---|
| GET | `/api/reports/metric/metrics?service=&range=` | `[{name: "order.amount"}, ...]`，当前服务指标目录 |
| GET | `/api/reports/metric/labels?service=&metric=&range=` | `[{key: "channel", values: ["app", "web"]}, ...]`，单指标标签候选 |
| GET | `/api/reports/metric/count?service=&metric=&range=&filters=` | `{metric, bucketSeconds, points: Point[]}`，一条 count 曲线 |

- `count` 为上报次数，不是上报数值总和或组合数；粒度由 `range` 决定。
- `filters` 为 URL 编码后的 JSON 对象，例如 `{"channel":["app","web"],"city":["上海"]}`；
  同键多值 OR、不同键 AND。省略或空对象表示全量，不预选任何标签。
- 全量包含真实组合和 `other`，每次上报只计一次。筛选匹配的数据已合入 `other` 且无法
  还原时为 `null / MERGED_OTHER`，不返回可见组合的部分和。确认无匹配为 `0 / ZERO`，
  丢弃、未采集及未来桶仍保持缺口。桶级质量字段沿用 §4.2。
- 当前范围没有该指标返回 404 / `NOT_FOUND`。非法 `filters` 返回 400 / `INVALID_PARAM`，
  不会退化为全量查询。最多 32 键、每键 100 值、JSON 16 KiB。重复值去重、空数组忽略。
- count 使用 `value_count`（数值观测次数），不能用一般调用 `count` 或 `value_sum`。
  历史按粒度选择一个源层级，保留标签身份后合成目标桶。标签元数据记录实际写桶归属，
  老历史缺元数据不能准确筛选时保持 `MERGED_OTHER` 缺口。
- 当前实现独立序列名额为小时内先到先得，count 不依赖重新选举 TopN；新元数据记录实际
  归属，不根据后续排名重写解释已落库身份。严格按次数重选并搬迁分布仍非本轮实现。
- 当前 count 页不增加数值统计、分位、环比或大盘钉选；既有旧接口继续保留。
- ClickHouse 执行 `migrations/2026-10-02-metric-heartbeat.sql` 后再部署应用。
  新桶按来源/key/时间/版本先去重快照，再汇总，避免分钟刷新与整点补刷重复计数。

### 4.8 依赖

| 方法 | 路径 | 说明 |
|---|---|---|
| GET | `/api/reports/dependency/upstream?service=&range=` | 上游列表 |
| GET | `/api/reports/dependency/downstream?service=&range=` | 下游列表 |
| GET | `/api/reports/dependency/series?service=&peer=&direction=&stat=&range=&bucket=` | 服务对趋势 |
| GET | `/api/reports/dependency/samples?service=&peer=&direction=&range=` | 代表性 Trace 入口 |

### 4.9 取样与 Trace

| 方法 | 路径 | 说明 |
|---|---|---|
| GET | `/api/reports/samples?kind=&service=&type=&name=&range=&instances=&problemCategory=` | 最近 **30** 条，事件时间倒序，多行 |
| GET | `/api/traces/{messageId}` | 组装 Trace；超 7 天 → 410 `TRACE_EXPIRED` |

**取样响应**

```json
{
  "items": [
    { "messageId": "order-…", "timestamp": 1790700000000, "durationMs": 812, "status": "fail",
      "summary": "POST /orders", "traceAvailable": true }
  ]
}
```

### 4.10 趋势响应统一结构

```json
{
  "service": "order", "kind": "TRANSACTION", "type": "URL", "name": "POST /orders",
  "stat": "HITS", "unit": "COUNT",
  "bucketSeconds": 600,
  "range": { "from": 1790696400000, "to": 1790700000000 },
  "points": [
    { "bucketStart": 1790696400000, "bucketEnd": 1790697000000, "value": 120,
      "partial": false, "quality": "OK", "realtime": false },
    { "bucketStart": 1790697000000, "bucketEnd": 1790697600000, "value": null,
      "partial": false, "quality": "DROPPED", "realtime": false },
    { "bucketStart": 1790697600000, "bucketEnd": 1790698200000, "value": 118,
      "partial": false, "quality": "OK", "realtime": true }
  ],
  "mom": { "kind": "DAY", "points": [ { "bucketStart": 1790610000000, "value": 96 } ] },
  "gaps": [ { "bucketStart": 1790697000000, "reason": "QUEUE_FULL" } ],
  "partitions": [ { "bucketStart": 1790697000000, "coveredSeconds": 300 } ]
}
```

要点：**`value: null` 表示缺口，绝不写 0**；`quality` ∈ `OK, ZERO, NO_DATA, DROPPED, PARTIAL, MERGED_OTHER, REALTIME`。

---

## 5. 大盘（dashboard）

| 方法 | 路径 | 权限 | 说明 |
|---|---|---|---|
| GET | `/api/dashboards?orgId=` | 叶子有效成员 | 该叶子的大盘列表 |
| POST | `/api/dashboards` | 叶子有效成员 | `{ orgId, name }`；非叶子 → 409 `NOT_LEAF` |
| POST | `/api/dashboards/{id}/rename` | 成员 | `{ name }` |
| DELETE | `/api/dashboards/{id}` | 成员 | 删除大盘与卡片 |
| POST | `/api/dashboards/{id}/cards` | 成员 | 新建卡片 |
| POST | `/api/cards/{cardId}` | 成员 | 更新卡片（目标/公式/统计项/维度范围/阈值线） |
| DELETE | `/api/cards/{cardId}` | 成员 | 删除卡片 → 通知 alert |
| POST | `/api/cards/reorder` | 成员 | `{ dashboardId, cardIds: [...] }` |
| GET | `/api/cards/{cardId}/series?range=&bucket=&dimension=ALL\|INSTANCE&mom=` | 成员 | 卡片序列 |
| GET | `/api/cards/{cardId}/instances?range=&page=&size=` | 成员 | 机器下钻明细 |

**卡片草稿**

```json
{
  "service": "order",
  "targetKind": "TRANSACTION",
  "targetType": "URL",
  "targetName": "POST /orders",
  "metricLabels": null,
  "formula": "sum(hits)",
  "timeRange": "RECENT_24H",
  "thresholdLines": [ { "direction": "ABOVE", "value": 200 } ]
}
```

**公式校验响应**

```json
{ "valid": false, "code": 40002, "message": "耗时与次数不能直接相加",
  "terms": [ { "expr": "avgDuration", "unit": "DURATION" }, { "expr": "hits", "unit": "COUNT" } ] }
```

**卡片序列响应**（在趋势结构上扩展）

```json
{
  "cardId": 12, "formula": "failures / hits", "unit": "RATE", "thresholdLines": [...],
  "points": [ { "bucketStart": 1790696400000, "value": 0.012, "quality": "OK" } ],
  "undefined": [ { "bucketStart": 1790698800000, "reason": "DIVIDE_BY_ZERO" } ],
  "gaps": [ { "bucketStart": 1790697000000, "missingInputs": ["hits"] } ]
}
```

**组织告警可选目标**

| 方法 | 路径 | 说明 |
|---|---|---|
| GET | `/api/dashboards/targets?orgId=` | 该叶子已引用**原始统计项**与**卡片结果**的并集 |
| POST | `/api/cards/{cardId}/to-alert` | 返回预填充的组织告警草稿（目标 + 公式） |

---

## 6. 告警（alert）

| 方法 | 路径 | 权限 | 说明 |
|---|---|---|---|
| GET | `/api/alerts?scope=&orgId=` | 服务告警：登录用户；组织告警：叶子成员 | 规则列表（不含任何触发历史） |
| GET | `/api/alerts/targets?scope=&service=&orgId=` | 同上 | 可选目标 |
| POST | `/api/alerts/preview` | 同上 | 草稿试算 → `{ result: "TRIGGER" \| "NO_TRIGGER" \| "INSUFFICIENT_DATA", points: [...] }` |
| POST | `/api/alerts` | 同上 | 保存；**始终 `enabled=false`** |
| POST | `/api/alerts/{id}` | 同上 | 编辑；若原为启用 → 自动关闭 + 窗口清零 |
| POST | `/api/alerts/{id}/enable` | 同上 | 手动启用；窗口从零开始 |
| POST | `/api/alerts/{id}/disable` | 同上 | 手动关闭 |
| DELETE | `/api/alerts/{id}` | 同上 | 删除 |

**规则请求体**

```json
{
  "scope": "ORGANIZATION", "orgId": 7,
  "name": "订单失败率突增", "description": "",
  "target": { "type": "CARD", "cardId": 12 },
  "combinator": "AND",
  "windowPoints": 3,
  "conditions": [
    { "stat": "FAILURE_RATE", "comparator": "GT", "threshold": 0.05 },
    { "stat": "HITS", "comparator": "GTE", "threshold": 10 }
  ],
  "recipients": [3, 8],
  "channels": ["EMAIL", "DINGTALK"]
}
```

**约束校验**

| 约束 | 违规 code |
|---|---|
| 服务告警：任意启用账号可作收件人 | `INVALID_RECIPIENT` |
| 组织告警：收件人必须是该叶子有效成员 | `NOT_ORG_MEMBER` |
| 组织告警：目标必须来自该叶子大盘指标并集 | `TARGET_NOT_REFERENCED` |
| 通道必须是平台已配置 | `CHANNEL_UNAVAILABLE` |
| 多条件共用同一 `windowPoints`，不支持逐条件点数 | 结构上不可表达 |
| 嵌套/括号表达式、环比、嵌套 OR 内 AND | `UNSUPPORTED_EXPRESSION` |

**一期不提供的端点**（明确不存在）：告警确认、P1/P2 严重度、告警历史、恢复通知、静默窗口、操作审计。

---

## 7. 平台配置（platform）

| 方法 | 路径 | 权限 | 说明 |
|---|---|---|---|
| GET | `/api/platform` | 登录 | `{ timezone, initialized, slow:{url,sql,call,cache}, channels:{email,dingtalk,feishu} }` |
| PUT | `/api/platform/slow-thresholds` | ADMIN | 只影响后续分析，不回算历史 |
| PUT | `/api/platform/channels` | ADMIN | 开关 + 凭据；未配置的通道不出现在告警选项 |
| — | — | — | **无修改时区的端点**（一期不允许） |

---

## 8. 前端路由与接口对应

| 路由 | 视图 | 主要接口 |
|---|---|---|
| `/login` | 登录 | `POST /api/login` |
| `/change-password` | 强制改密 | `POST /api/me/password` |
| `/services` | 服务列表 | `GET /api/services` |
| `/svc/:service/transaction` | Transaction Type 列表 | `/api/reports/transaction/types` |
| `/svc/:service/transaction/:type` | Name 列表 | `/api/reports/transaction/names` |
| `/svc/:service/transaction/:type/:name` | 趋势 + 取样 | `series` + `machines` + `samples` |
| `/svc/:service/event…` | Event 三段 | `/api/reports/event/*` |
| `/svc/:service/problem` | Problem 五类列表 | `/api/reports/problem/categories` + `/names` |
| `/svc/:service/problem/:type/:name` | Problem 趋势 + 取样 | 通用 `series` + `samples`（`kind=PROBLEM`）+ `machines` |
| `/svc/:service/heartbeat` | Heartbeat | `/api/reports/heartbeat/*` |
| `/svc/:service/metric` | Metric | `/api/reports/metric/*` |
| `/svc/:service/metric/:metric` | Metric count 详情 | `/api/reports/metric/labels`、`/api/reports/metric/count` |
| `/svc/:service/dependency` | 依赖 | `/api/reports/dependency/*` |
| `/trace/:messageId` | Trace | `GET /api/traces/{id}` |
| `/dashboards` | 我的叶子大盘列表 | `GET /api/orgs/mine` + `/api/dashboards` |
| `/dashboards/:id` | 大盘与卡片 | `/api/dashboards/{id}` + `/api/cards/{id}/series` |
| `/alerts` | 告警规则 | `/api/alerts` |
| `/admin/users` | 账号管理 | `/api/users` |
| `/admin/orgs` | 组织树 | `/api/orgs` |
| `/admin/platform` | 平台配置 | `/api/platform` |

---

## 9. Mock 开关

```ts
// src/api/client.ts
const USE_MOCK = import.meta.env.VITE_USE_MOCK !== "false";   // 默认 true
```

- `USE_MOCK = true`（默认）：所有 `/api/**` 请求由 MSW 拦截，返回 `src/mock/` 数据集。
- `USE_MOCK = false`：直接 `fetch`，走 Vite proxy → `http://localhost:8080`。
- 开关只在一处判断，业务代码无感；MSW 在 `USE_MOCK=false` 时不注册 Service Worker。
