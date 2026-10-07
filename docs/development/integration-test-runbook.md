# NeoCat 全功能集成测试操作手册（待现场执行）

> 范围：`docs/neocat-technical-design/00-scope-and-traceability.md` 的 32 条链路，加上真实 Spring 装配、MySQL/ClickHouse 往返、Apollo 失败启动、权限/事务/故障边界。此文档**不是已通过报告**；每条用例在真实环境执行后填入结果和证据。先读 [中间件异机部署文档](middleware-docker-deployment.md)。当前生产通知适配器 `UnsupportedExternalNotifier` 会抛异常；邮件/钉钉/飞书只能验逐通道失败记录，不能验“已投递”。

## 1. 执行台账与门槛

在**隔离测试库**执行；生产已有库不得重放新库 SQL。记录：执行日期/人、应用版本/构建产物校验和、JDK/Node/MySQL/ClickHouse/Apollo 版本、中间件内网地址、Spring/前端启动命令、Apollo 发布版本、库备份位置（不记录密码）、平台时区、每项 PASS/FAIL/BLOCKED/NOT_RUN + 证据链接。失败时记 HTTP 状态、JSON `code` 或 Protobuf 响应、脱敏日志、相关 SQL 查询及时间戳；**不能把接口返回 202 当作后续分析成功**。

前置：中间件按部署文档完成（新库十一张 ClickHouse 表，含 `nc_metric_label_metadata`；已有库须在副本演练、备份并审批执行 `migrations/2026-10-02-metric-heartbeat.sql`，见部署文档 §2.1；MySQL 全新 schema 或经验证迁移；Apollo 已发布完整 `application`），应用机能访问 Apollo Meta 返回的 Config URL、MySQL 3306、ClickHouse HTTP 8123。JDK 17+、Maven、Node、npm、`protoc`、`curl`、`jq`、MySQL/ClickHouse 查询客户端可用；使用项目当前 Java 版本以 `pom.xml` 为准。执行数据库只读核查：

```bash
mysql -h <MYSQL_HOST> -u <NEOCAT_USER> -p neocat -e 'SELECT initialized,timezone FROM nc_platform_profile; SHOW TABLES;'
curl --fail 'http://<APOLLO_META>:8080/services/config?appId=neocat'  # 不把发现后的配置原文公开
# ClickHouse：用客户端交互认证，执行 SHOW TABLES FROM neocat（应有 11 表）
# 逐级 DESCRIBE nc_{minute,hour,day,week,month}_bucket 与 nc_metric_label_metadata
# 核对 count/missing/last/time/snapshot 列和元数据 source，不能仅数表
```

**重要的前端阻断项**：当前 `front/vite.config.ts` 仅配置 `server.port=5173`，**没有 `/api` 代理**；`front/src/api/client.ts` 真实模式发相对 `/api` 请求，直接运行 `VITE_USE_MOCK=false npm run dev` 会请求 Vite 5173 而非 Spring 8080。前端真实联调须先由开发在隔离分支给 Vite `server` 增加 `proxy: { '/api': { target: 'http://127.0.0.1:8080', changeOrigin: true } }` 并重启，或用**同源反向代理**同时转发页面和 `/api`（保持 Cookie 同源）；DevTools Network 确认 `/api/platform/init-status` 非 Vite HTML/404 且由后端响应后再开始 UI 用例。没有该前置条件，前端项记 BLOCKED，不能用 mock 截图冒充。

应用机从仓库根构建并启动后端（配置由 Apollo 客户端加载，必须提供 `-Dapollo.meta`/`APOLLO_META`）：

```bash
mvn -f backend/pom.xml -DskipTests package
cd backend
mvn org.springframework.boot:spring-boot-maven-plugin:3.3.13:run -Dspring-boot.run.jvmArguments="-Dapp.id=neocat -Dapollo.meta=http://<APOLLO_META>:8080"
# 另开终端，成功启动后：
curl -i http://127.0.0.1:8080/api/platform/init-status
```

`spring-boot:run` 直接调用 `NeoCatApplication.main` 做在线配置预检。根 POM 已删除，当前 backend POM 未隐式绑定 `repackage`；jar 部署在仓库根显式执行 `mvn -f backend/pom.xml -DskipTests package org.springframework.boot:spring-boot-maven-plugin:3.3.13:repackage`，再用 `java -Dapp.id=neocat -Dapollo.meta=http://<APOLLO_META>:8080 -jar backend/target/neocat-backend-0.1.0-SNAPSHOT.jar` 启动。本地已验证 manifest 的 JarLauncher/Start-Class 及 BOOT-INF 依赖，但未证明现场启动成功；普通 `package` 产物不可直接当 Boot jar。构建、校验和与前端同源部署见部署文档 §6。期望未初始化为 `{"initialized":false}`；若已有隔离数据为 true，跳过**不可重复**的初始化项并标记“已有数据”，需要完整初始化用例则创建新的隔离库。启动失败记完整首个异常栈与 Bean 名；不得用内存替身代替生产连接。Apollo 断线/缺键试验**只在复制环境中**进行，见 T00。应用机与中间件机端口 8080 属不同主机。

示例 HTTP 请求以 `BASE=http://127.0.0.1:8080`、`COOKIE_JAR=$(mktemp)` 为前置。口令由操作者交互取得，示例占位符不可原样使用。`curl -b "$COOKIE_JAR" -c "$COOKIE_JAR"` 保持会话；`jq` 从返回提取 ID 并记录到本地受限文件。勿公开 Cookie、密码或 Apollo 配置。

## 2. 基础操作：先获得可重复的测试主体

在**全新隔离库**：

```bash
export BASE=http://127.0.0.1:8080
export COOKIE_JAR="$(mktemp)"
chmod 600 "$COOKIE_JAR"
curl -sS -i -X POST "$BASE/api/platform/initialize" -H 'Content-Type: application/json' \
  -d '{"timezone":"Asia/Shanghai","adminUsername":"root_it","adminPassword":"<测试用强密码>"}'
curl -sS -i -b "$COOKIE_JAR" -c "$COOKIE_JAR" -X POST "$BASE/api/login" \
  -H 'Content-Type: application/json' \
  -d '{"username":"root_it","password":"<同上>"}'
curl -sS -b "$COOKIE_JAR" "$BASE/api/me" | jq .
# 首登返回 mustChangePassword=true 时（当前 createInitialSuperAdmin 如此创建），先改密：
curl -sS -i -b "$COOKIE_JAR" -X POST "$BASE/api/me/password" \
  -H 'Content-Type: application/json' \
  -d '{"oldPassword":"<初始强密码>","newPassword":"<新的强密码>"}'
# 如果当前会话失效，用新口令重新 /api/login，刷新 COOKIE_JAR，再执行管理接口。
```

创建 `it_user` 和 `it_admin`（使用 `/api/users` 返回的 `id`），分别在**独立 Cookie jar** 登录，必要时完成强制改密后继续。创建根组织 `it_root`，叶子 `it_leaf` 和另一个隔离叶子 `it_other`，把 `it_user` 加入根/叶以验证继承及撤销。ID 从实际 HTTP 返回提取，不把数据库自增 ID 写死。负例请求用另一 jar/匿名执行。以下测试按顺序执行；耗时分钟/小时任务需按**平台时区**的完整桶、实际刷新延时观察，不用固定 `sleep 70` 推断成功。

下面命令接续本节登录后的超管 Cookie（将 `<...>` 和口令替换成现场值，Cookie 文件勿共享）；每次 POST/DELETE 后立即 GET 并在独立会话验证权限。若强制改密，先调用 `/api/me/password` 再继续：

```bash
USER_ID=$(curl -fsS -b "$COOKIE_JAR" -H 'Content-Type: application/json' \
  -d '{"username":"it_user","password":"<强密码>"}' "$BASE/api/users" | jq -er '.id')
ROOT_ID=$(curl -fsS -b "$COOKIE_JAR" -H 'Content-Type: application/json' \
  -d '{"name":"it_root","parentId":null}' "$BASE/api/orgs" | jq -er '.id')
LEAF_ID=$(curl -fsS -b "$COOKIE_JAR" -H 'Content-Type: application/json' \
  -d "{\"name\":\"it_leaf\",\"parentId\":$ROOT_ID}" "$BASE/api/orgs" | jq -er '.id')
curl -i -b "$COOKIE_JAR" -X POST "$BASE/api/orgs/$ROOT_ID/members" \
  -H 'Content-Type: application/json' -d "{\"userId\":$USER_ID}"
curl -fsS -b "$COOKIE_JAR" "$BASE/api/orgs/$LEAF_ID/deletion-preview" | jq .
curl -fsS -b "$COOKIE_JAR" "$BASE/api/orgs/mine" | jq .
```

期望各次创建返回 201，成员添加成功、预览有正确组织名。建盘应使用实际拥有该叶权限的用户 Cookie，**超管没有组织成员旁路**；超管也可在隔离环境先把自己加入测试叶子，再用其 Cookie 创建。失败时不要继续插入后续对象，先查 HTTP body/SQL 排查。

## 3. 链路用例矩阵（每行记录 PASS/FAIL/BLOCKED、时间与证据）

| ID / 追溯链路 | 操作（在隔离环境） | 通过标准与应保存的证据 |
|---|---|---|
| T01 / 1 初始化 | 查 `/api/platform/init-status`；首次 POST `/api/platform/initialize`；再调用一次；登录超管，查 `/api/platform`、MySQL `nc_platform_profile/nc_account/nc_channel_config`。 | 首次成功且固定时区、三个通道初始关闭；重复初始化拒绝；MySQL 仅一份平台记录且超管 BCrypt 密码散列。记录 HTTP/SQL。 |
| T02 / 2 建号 | 超管 POST `/api/users` 创建普通用户；重复用户名、短密码各试一次；用新用户登录并查列表。 | 创建为 USER、强制改密；重复/短密码拒绝且账户数不变；普通用户访问 `/api/users` 为 403。 |
| T03 / 3 登录 | 匿名 `/api/me`，错误用户名和错误口令，正确登录，两会话、登出其中之一；查 `nc_session` 的到期/续期。 | 匿名 401；错误凭据不泄露用户存在性；正确登录 Set-Cookie `NC_SESSION` HttpOnly、Path=/，另一个会话不受单会话登出影响；有效请求滑动续期。 |
| T04 / 4 重置 | 管理员 POST `/api/users/{id}/password/reset`；旧 Cookie 访问、旧密码登录、新密码登录并尝试普通接口，再 POST `/api/me/password`。 | 旧会话吊销、旧密码失效；新登录只允许改密/登出/查自身，改密后可正常访问；SQL 中仅密码散列。 |
| T05 / 5 授权 | 超管 POST `/api/users/{id}/role` 传 `{"role":"ADMIN"}`，普通 ADMIN 再尝试给别人授 ADMIN，超管尝试改自己。 | 只有超管可改别人角色；ADMIN/改自己被拒；查询账号与重新登录权限一致。 |
| T06 / 6 禁用启用 | 给用户添加组织成员及告警收件人（见 T30），禁用后查会话、成员、收件人；启用并登录。 | 禁用吊销会话/移除收件人，成员关联保留但有效权限不放行；启用恢复成员资格，不自动恢复收件人；MySQL/HTTP 交叉核对。 |
| T07 / 7 建组织树 | POST `/api/orgs` 建根、子叶、同父重复名；GET `/api/orgs`；非管理员创建。 | 父子/leaf/成员数正确；同父重复拒绝，非管理员 403；MySQL `nc_org_node` 一致。 |
| T08 / 8 管成员 | 根添加用户后 GET `/api/orgs/mine`，叶直加/移除成员、再移除根成员；另一用户/管理员（非成员）访问 `/api/dashboards?orgId=...`。 | 继承直系 + 祖先，即时撤权；管理员无组织资源旁路；`nc_org_member/nc_effective_leaf` 与访问一致。 |
| T09 / 9 改拓扑 | 叶子无资源时新建子节点；另在有大盘/组织告警的叶子下尝试新建；重命名并查看预览。 | 无资源可改为非叶；有资源拒绝且拓扑不变；`nc_org_resource` 与源表一致，任何不一致都拒绝放行。 |
| T10 / 10 删叶 | 对有大盘/卡片/组织告警的叶子 GET `/api/orgs/{id}/deletion-preview`；错误 confirmName、非叶删除、正确 `DELETE ...?confirmName=`。 | 预览计数与源表相同；前两种拒绝且无副作用；正确删除原子清理资源/投影/成员，其他组织不受影响；事务失败注入见 T34。 |
| T11 / 11 本地上报 | `client-java` 注入自行实现的真实 HTTP `MessageSender` 发送 v1 树（仓库**没有内置 HTTP sender**）；或按 §4 `protoc` 生成二进制发送。 | HTTP 202 Protobuf ACCEPTED 仅代表受理；随后查 `nc_service/nc_instance`、ClickHouse 落库及报表。当前独立令牌上报路径受鉴权冲突阻断；SDK 停机 flush/发送失败不抛业务异常需在真实 sender 下另测。 |
| T12 / 12 校验 | 带有效登录 Cookie 分别请求空树、错误版本、坏 Protobuf、无效字段/超大小/节点数；另以无 Cookie、仅错/正确 `X-NC-Token` 发送。 | 已鉴权请求的格式/容量错误按 HTTP 400 等状态返回 Protobuf `REJECTED` 与错误码，不能写入报表；当前 Controller 未读取令牌，无 Cookie 的请求预计被会话拦截并返回 **401 JSON**，不是 ingest Protobuf；若被放行则记录安全缺陷，不判令牌验证通过。 |
| T13 / 13 发现 | 首报新服务/实例，用 `/api/services?kind=TRANSACTION&range=RECENT_1H` 及 `/api/services/{service}/instances?...`，核查 `nc_service/nc_instance`。 | 身份校验通过即发现；目录按范围/类型仅显示有数据项。异步报表需等完整分钟/实际数据落库后再判断筛选。 |
| T14 / 14 幂等 | 相同 `message_id`/相同内容重复报，再用相同 ID 改耗时发送；查 ClickHouse `nc_quality_event` 与聚合。 | 同内容 `DUPLICATE`，数量不增加；异内容 409 `REJECTED`/ID_CONFLICT 并有质量事件。重启后幂等窗口语义另行核验，不能声称永久去重。 |
| T15 / 15 过载 | 在专用实例将 Apollo `neocat.ingest.queue.capacity` 调小（记录发布版本），并发突发上传；查 `/api/v1/ingest/stats`、目录和质量表；还原配置。 | 若实际触发队列满：202 `DROPPED`、丢弃计数增、仍发现服务；未触发则记 BLOCKED/重试并记载压力参数，绝不臆测。 |
| T16 / 16 实时分析 | 用含 Transaction、Event、Heartbeat、Metric、RemoteCall、错误及慢节点的树，核对当前小时与落库后多域行、`nc_quality_event`。 | 七域扇出产物与事件时间一致；隔离实例模拟某分析器故障时其他域不被静默抹掉，失败有质量事件。无安全故障注入入口时标 BLOCKED。 |
| T17 / 17 Transaction | UI 服务→Type→Name→趋势→样本→Trace；API `/api/reports/transaction/types`、`/names`、`/api/reports/series?kind=TRANSACTION...`、`/samples`、`/api/traces/{messageId}`。 | 类型/名称/实例筛选、Hits/失败/QPS/分位与 ClickHouse 行一致；默认趋势 `stat=HITS`；样本倒序且默认最多 30；Trace 树根和缺失分支正确。 |
| T18 / 18 Event | 上报成功/失败事件，查 `/api/reports/event/types`、`/names`、`/series?kind=EVENT` 与 UI。 | 分类、次数与失败统计正确；Event 不显示耗时分位；SQL 与前端一致。 |
| T19 / 19 Problem | 发异常 URL、超过各阈值的 URL/SQL/CALL/CACHE，查 `/api/reports/problem/categories`、`/names`。 | 五类可重叠；异常无分位、慢类有；调整 `/api/platform/slow-thresholds` 只影响后续上报，不回改旧行。 |
| T20 / 20 Heartbeat | 发送两个 JVM 实例、20 项完整与部分缺失的 presence-aware Heartbeat；查 `/api/reports/heartbeat/metrics`、`/instances`、`/series` 和 UI。同桶乱序/重复采样、跨整点重启、累计 GC 计数分别复测。 | 固定 20 项编目；每实例独立曲线、按事件时间最后采样、无环比、不跨 JVM 求和、不累加累计 GC；未上报/未知池/缺失字段和旧 sum 历史显示 null，不补零。`logJvmHeartbeat()` 无内置定时器，标准 sampler 不猜测 Full GC，可靠外部来源才提供 fullGcCount/Time；编目完整不代表每个 JVM 能采齐。 |
| T21 / 21 Metric | 发送同名不同标签 Metric，查 `/api/reports/metric/metrics`、`/labels`、`/count`；测试全量、同键多值、异键组合、标签分隔符、专用低 Top N 实例、跨小时/日与旧数据窗口。 | count 是观测次数而非 value sum，一条线；全量含 other 一次，同键 OR、异键 AND；无法恢复返回 null/MERGED_OTHER，无采集 NO_DATA、未来 NOT_OCCURRED；缺失不补零，不显示可见子集伪装完整；`nc_metric_hour_rank`、`nc_metric_label_metadata` 与五级桶交叉核对，重复快照不得重复累计。旧 `/metric/list` 非本次前端 count 的验收出口。 |
| T22 / 22 依赖 | 发带 `remote_call.downstream_service` 的上游树，再分别有/无下游树；查 `/api/reports/dependency/downstream` 与 `/upstream`、Trace。 | 边统计不依赖下游树是否存在；缺失在 Trace 显示，两个方向及耗时/失败与事实表一致。 |
| T23 / 23 时间留存 | `/api/reports/series` 比较 RECENT_1H、TODAY、THIS_WEEK、HOUR/DAY/WEEK/MONTH、`bucket`、`mom` 与实例集合；跨整点重启。 | `bucketStart/end` 左闭右开、partial/coveredSeconds、`quality=NO_DATA` 时 value=null，QPS 当前小时用已覆盖秒数；分位先合并分布；小时、日周月滚动及 TTL 在实际时间/受控时钟下验证；不能通过直接删历史代替 TTL 验证。 |
| T24 / 24 创建大盘 | 成员 POST `/api/dashboards` `{"orgId":<leafId>,"name":"it-board"}`；列表/改名；非叶、非成员、管理员无成员各试。 | 201，MySQL `nc_dashboard` 和 `nc_org_resource` 同步；拒绝分支不留下投影，查询权限准确。 |
| T25 / 25 卡片 | POST `/api/dashboards/{id}/cards` 以 §5 草稿创建；改公式/阈值线，读 `/api/cards?dashboardId=`、`/api/cards/{id}/series`、删除。 | 合法公式单位一致；非法单位/除零/缺数分别被拒或显示不同 outcome；阈值线只是展示。当前 `DashboardController.CardDraft.toCard()` 使用 `Card.withoutThresholds`，**可能丢弃请求里的阈值线**，必须真实读回，不可据接口 201 判通过；卡片和投影 card_count 同步。 |
| T26 / 26 服务告警 | GET `/api/alerts/channels`，启用平台通道后，POST `/api/alerts` 创建 SERVICE/RAW_METRIC 草稿并 GET `/api/alerts?scope=SERVICE`。 | 通道未配置不可选；创建后 `enabled=false`，规则、条件、通道及收件人在 MySQL 往返一致；不能把创建当成已投递。 |
| T27 / 27 组织告警 | 先建有原始统计项的卡片；GET `/api/dashboards/targets?orgId=...`；用 CARD_RESULT 创建 ORGANIZATION 规则；非成员操作。 | 可选目标为原始项∪卡片结果；非叶/非成员拒绝；源表和投影同步，重启后目标仍正确。 |
| T28 / 28 预告警 | POST `/api/alerts/preview` 用明确能满足/不满足/缺数的连续完整分钟样本；前后查询规则、窗口、日志。 | 返回三态及 `points[].known/satisfied/missingStat`；不发消息、不新增历史、不改变启停/窗口。 |
| T29 / 29 启用 | POST `/api/alerts/{id}/enable`，在完整分钟点前后发送样本，再 `/disable`。 | 启用后仅从启用时刻后的完整分钟建 X 点窗口；禁用不触发；检查 `nc_alert_window_state`。 |
| T30 / 30 运行告警 | X=3，连续 3 个满足点、一个缺数、再次 3 点；测 AND/OR，配置两个通道与收件人，查逐通道日志。 | 第三点才触发，缺数打断；同分钟去重；通知适配器抛不支持异常时逐通道**失败不计成功**，无站内历史；不能声称邮件/钉钉/飞书送达。 |
| T31 / 31 规则变更 | 启用后 POST `/api/alerts/{id}` 编辑；变更卡片目标/删除卡片，读规则与窗口。 | 编辑自动关闭、窗口清零；卡片变更同步目标，卡片删除使关联规则 invalid 但保留配置；其他规则无影响。 |
| T32 / 32 收件人变化 | 从叶移除成员、禁用用户，观察规则 recipients 与 channels；最后收件人被移除后重新加人。 | 失权/禁用移除对应收件人，规则通道字段不随最后收件人清零，启用但无人仍判定不送；补人重建窗口；重启后数据仍一致。 |

## 4. 可复制的 Protobuf 上报基线

在仓库根目录运行（`protoc` 的 message 名称以 `proto/neocat/ingest/v1/ingest.proto` 为准）：

```bash
TS_MS=$(($(date +%s)*1000))
ID="it-$(date +%s)-$$"
protoc --encode=neocat.ingest.v1.IngestRequest -I proto proto/neocat/ingest/v1/ingest.proto > /tmp/neocat-it.bin <<EOF
protocol_version: "1.0"
trees {
  service_name: "it-order" instance_id: "it-host-1"
  message_id: "$ID" root_message_id: "$ID"
  tree_timestamp: $TS_MS
  nodes {
    node_id: "n1" kind: TRANSACTION category: "URL" name: "GET /it"
    status: "0" timestamp: $TS_MS duration_ms: 42
  }
  nodes {
    node_id: "n2" kind: EVENT category: "business" name: "it-done"
    status: "0" timestamp: $TS_MS
  }
}
EOF
curl -sS -o /tmp/neocat-it-response.bin -w 'HTTP %{http_code}\n' \
  -X POST "$BASE/api/v1/ingest" -H 'Content-Type: application/x-protobuf' \
  -b "$COOKIE_JAR" \
  --data-binary @/tmp/neocat-it.bin
protoc --decode=neocat.ingest.v1.IngestResponse -I proto \
  proto/neocat/ingest/v1/ingest.proto < /tmp/neocat-it-response.bin
```

期望 202、`status: ACCEPTED`, `accepted_trees: 1`。**当前 `SessionWebConfiguration` 拦截所有 `/api/**`，包含 `/api/v1/ingest`；`IngestController` 未读取 `X-NC-Token`，因此上述示例使用登录 Cookie。**这与“SDK 用独立令牌上报”的预期不一致：另用无 Cookie、仅带令牌发送，应记为安全/契约阻断项，不能把带 Cookie 的成功视为 SDK 直连通过。Apollo 目前还强制 `auth-token` 非空，但不等于服务端校验它；修复鉴权前 T11 的真实 SDK 直连子项 BLOCKED。勿在共享历史/截图泄露令牌。同一二进制再发用于 T14；ID 冲突必须保持 ID 不变、仅修改字段后重新编码。待完整分钟/刷库后查询（**真实路径是 `/api/reports/series`，不是 `/transaction/series`**）：

```bash
curl -sS -b "$COOKIE_JAR" \
  "$BASE/api/reports/series?service=it-order&kind=TRANSACTION&type=URL&name=GET%20%2Fit&range=RECENT_1H" | jq .
```

用两个不同实例、故障节点、Metric/Heartbeat/RemoteCall 与跨服务 `root_message_id` 继续扩充样本；提交前记录实际 payload 的 SHA256 和时间戳。重要：当前 `Trace` 采样率低于 1 时不能要求每条树都能下钻。生成过期数据时以**当前平台时区自然小时**计算，而非仅用固定“3 小时前”猜测。

## 5. 大盘、告警可操作示例与真实 SQL 判据

在已有 `it_leaf` 与 `it-order` 的前提下，先通过 UI 构造合法卡片；HTTP 草稿形状：

```json
{"service":"it-order","targetKind":"TRANSACTION","targetType":"URL","targetName":"GET /it","metricLabels":"","formula":"hits","timeRange":"RECENT_24H","thresholdLines":[]}
```

实际 `formula` 与字段组合以 UI 保存生成的请求及 `/api/cards` 响应复核；示例 `HITS` 属当前 `FormulaParser` 可解析统计项，但**不自行直改数据库以制造成功**。服务规则草稿需 `scope=SERVICE`, `target:{kind:"RAW_METRIC",cardId:0,service:"it-order",reportKind:"TRANSACTION",type:"URL",name:"GET /it",formulaStats:[]}`, `conditions:[{stat:"HITS",comparator:"GT",threshold:0}]`, `windowPoints:3`, `combinator:"AND"`, `recipients:[<userId>]`, `channels:["EMAIL"]`；组织规则改 `scope=ORGANIZATION`, `orgId=<leafId>`, `target.kind=CARD_RESULT`, `target.cardId=<cardId>`。通道设置 API `PUT /api/platform/channels` body `{"email":true,"dingtalk":false,"feishu":false}` 当前控制器只写开关、凭据为 null；**开启开关不表示通道能投递**，适配器仍会拒绝真实发送。禁用测试后恢复开关。

以下用例在现场可以直接替换 ID 后执行；先确认当前登录 Cookie 为 `LEAF_ID` 的有效成员（建盘），平台开关需使用管理员 Cookie（切换后不要误用普通成员）。

```bash
BOARD_ID=$(curl -fsS -b "$COOKIE_JAR" -H 'Content-Type: application/json' \
  -d "{\"orgId\":$LEAF_ID,\"name\":\"it-board\"}" "$BASE/api/dashboards" | jq -er '.id')
CARD_ID=$(curl -fsS -b "$COOKIE_JAR" -H 'Content-Type: application/json' \
  -d '{"service":"it-order","targetKind":"TRANSACTION","targetType":"URL","targetName":"GET /it","metricLabels":"","formula":"HITS","timeRange":"RECENT_24H","thresholdLines":[]}' \
  "$BASE/api/dashboards/$BOARD_ID/cards" | jq -er '.id')
curl -fsS -b "$COOKIE_JAR" "$BASE/api/cards?dashboardId=$BOARD_ID" | jq .
curl -fsS -b "$COOKIE_JAR" "$BASE/api/dashboards/targets?orgId=$LEAF_ID" | jq .
curl -i -b "$COOKIE_JAR" -X PUT "$BASE/api/platform/channels" \
  -H 'Content-Type: application/json' -d '{"email":true,"dingtalk":false,"feishu":false}'
RULE_ID=$(curl -fsS -b "$COOKIE_JAR" -H 'Content-Type: application/json' \
  -d "{\"scope\":\"SERVICE\",\"name\":\"it-service-rule\",\"target\":{\"kind\":\"RAW_METRIC\",\"cardId\":0,\"service\":\"it-order\",\"reportKind\":\"TRANSACTION\",\"type\":\"URL\",\"name\":\"GET /it\",\"formulaStats\":[]},\"combinator\":\"AND\",\"windowPoints\":3,\"conditions\":[{\"stat\":\"HITS\",\"comparator\":\"GT\",\"threshold\":0}],\"recipients\":[$USER_ID],\"channels\":[\"EMAIL\"]}" \
  "$BASE/api/alerts" | jq -er '.id')
curl -fsS -b "$COOKIE_JAR" -X POST "$BASE/api/alerts/$RULE_ID/enable" | jq .
curl -fsS -b "$COOKIE_JAR" "$BASE/api/alerts?scope=SERVICE" | jq .
```

期望 POST 大盘/卡片/规则为 201；规则创建初始关闭，显式启用才变更状态，重启后 MySQL 和读接口保持一致。若后台账号缺少叶子权限或规则不满足保存条件，HTTP 非 2xx 应记录真实缺陷/拒绝原因而非手工改表。`HITS` 由当前 `FormulaParser`/`Stat` 支持；生产接口仍需现场验证 `CardDraft` 的字段与存储适配器一致。

使用只读 SQL 判据（按实际 ID 过滤，不展示口令/会话原文）：

```sql
-- MySQL/neocat：投影与源数据总数核对，差异必须判失败
SELECT (SELECT COUNT(*) FROM nc_dashboard) AS dashboards,
       (SELECT COUNT(*) FROM nc_org_resource WHERE resource_kind='DASHBOARD') AS projected_dashboards,
       (SELECT COUNT(*) FROM nc_alert_rule WHERE org_id IS NOT NULL) AS org_rules,
       (SELECT COUNT(*) FROM nc_org_resource WHERE resource_kind='ALERT_RULE') AS projected_rules;
SELECT id,scope,channels,enabled,invalid FROM nc_alert_rule WHERE name LIKE 'it-%';
SELECT org_id,resource_kind,resource_id,card_count FROM nc_org_resource WHERE org_id=<leafId>;
-- 无外键：删除必须由应用显式级联，以下四个查询结果都必须为 0 行（悬挂引用即缺陷）
SELECT c.id FROM nc_card c LEFT JOIN nc_dashboard d ON d.id = c.dashboard_id WHERE d.id IS NULL;
SELECT t.id FROM nc_card_threshold_line t LEFT JOIN nc_card c ON c.id = t.card_id WHERE c.id IS NULL;
SELECT x.id FROM nc_alert_condition x LEFT JOIN nc_alert_rule r ON r.id = x.rule_id WHERE r.id IS NULL;
SELECT w.rule_id FROM nc_alert_window_state w LEFT JOIN nc_alert_rule r ON r.id = w.rule_id WHERE r.id IS NULL;
-- ClickHouse/neocat：同一服务/时间桶中核对明细、原始树、质量事件和聚合
SELECT kind,type,name,minute,sum(count) FROM neocat.nc_minute_bucket
 WHERE service='it-order' GROUP BY kind,type,name,minute ORDER BY minute DESC LIMIT 30;
SELECT count() FROM neocat.nc_raw_tree WHERE service='it-order';
SELECT event_type,service,event_time FROM neocat.nc_quality_event ORDER BY event_time DESC LIMIT 10;
```

上面 SQL 用 `<leafId>` 需替换为纯数字；ClickHouse 字段名以实际新表执行结果为准，若 SQL 报列不匹配，应记联调缺陷，不应改写数据迁就。**当前 MySQL schema 已不使用外键，删除叶子/大盘/卡片/规则的级联由应用在同一事务内完成**；因此 T10、T25、T31、T32 每次删除后都要跑上面的悬挂查询，仅“接口返回 204/200”不足以判删除正确。

## 6. 专项故障、事务、前端验收

本轮规范化重构新增 MySQL 锁表；已有库升级前审批执行
`migrations/2026-10-03-distributed-lock.sql`，并逐项执行
[真实双连接锁验收](mysql-lock-verification.md)，归入 T34 附加证据，初始状态 **NOT_RUN**。
新 DTO / Convert 不增加响应外层包装；T39 同时核对 null / 省略字段 / 空集合、HTTP 状态码、
Cookie 和时间数值，不仅核对“请求成功”。离线 Jackson / MockMvc / Spring 扫描规格是回归证据，
不替代 T33 的 Apollo、真实 Mapper 和数据源装配验收。卡片入口目前仍忽略请求阈值线，
告警 `AlertRuleService.save` 仍只生成关闭状态返回对象而未调用仓储；这两项既有缺口需在现场明确记录，
不能因 DTO 改造而宣称“写入全部字段”已通过。

| ID | 操作 | 期望 / 证据 |
|---|---|---|
| T00 Apollo 启动失败 | 复制隔离应用实例，错误 Meta 地址启动；恢复地址后复制应用命名空间并临时移除 `neocat.ingest.queue.capacity`（不能影响共享环境），重启。 | 两次均在业务端口开放前失败；日志分别为在线读取失败/缺必需键。现有缓存不可让应用启动；恢复发布版本并复验。 |
| T39 请求体 JSON 绑定 | 对每个 `@RequestBody` 端点发含全部字段的非空 JSON：`/api/platform/initialize`、`/api/login`、`/api/me/password`、`/api/users`、`/api/users/{id}/role`、`/api/orgs`、`/api/orgs/{id}/members`、`/api/dashboards`、`/api/dashboards/{id}/cards`、`/api/alerts`、`PUT /api/platform/channels`、`PUT /api/platform/slow-thresholds`。 | **每个字段都真实落入响应或库，无一为 null/缺省**。领域对象由 `record` 改为普通类 + Lombok 后，JSON→构造器绑定依赖编译期 `-parameters`（当前已在 `pom.xml` 开启，实测 `MethodParameters` 存在）；一旦该参数丢失，字段会静默变 `null` 而 **Groovy mock 单测测不出**。任一字段丢失即记阻断缺陷，不判通过。 |
| T33 生产装配与数据库往返 | 正常 `main` 启动，逐个 CRUD/查询身份、组织、平台、目录、卡片、规则；重启后重查 MySQL；上报并跨整点重查 ClickHouse 分钟/小时/原始树。 | 无 Bean 冲突、Mapper 找不到/SQL 列异常；数据库真实读回与 HTTP 相符。仅单测通过**不能**判 T33 通过。 |
| T34 组织事务/投影并发 | 隔离环境并发建盘/建规则与删叶；在可控断点令投影写入失败，查主表/投影/事件传播；再故意造一行不一致的**隔离**投影，尝试删除，随后恢复数据。 | 事务失败所有相关写入一起回滚；不一致拒删、不误删；并发提交后投影与主表一一对应。缺少安全故障注入手段记 BLOCKED，不在共享库人为改表。 |
| T35 调度与清理 | 在真实分钟 `:05`、整点后 `:02`、每日清理窗口观察应用日志及 ClickHouse 小时/日周月聚合与 TTL。 | 无重复累计/错时区，保留期符合 Apollo；没有等到周期或无受控时钟不得宣布通过。 |
| T36 服务故障与恢复 | 隔离环境停止 ClickHouse 或断连接，上报/查询；恢复后检查错误、质量记录及补偿边界；MySQL 连接失败同理。 | 错误不得伪装成功/零值，已承诺但未落库的数据逐项标记风险；恢复后重新跑 T11–T23。已有共享实例不可直接停机。 |
| T37 前端真后端 | 先满足 §1 同源代理前置，`cd front && VITE_USE_MOCK=false npm run dev`；浏览器用真实账号完成 T01–T32 可见页面，DevTools Network 查 `/api` 响应。 | `/api` 来自 8080 而非 MSW/Vite HTML；页面读写刷新后仍在，错误提示匹配响应 `code`，缺数显示缺口，组织拒绝不可绕过。`npm run flow`/默认 mock 仅作前端自检，**不算本项**。 |
| T38 性能与边界 | 采集上报 P99、消费延迟、报表 P95、告警判定延迟；超限 batch/超节点、多实例分页、夜间 TTL 各压测。 | 按 `00-scope-and-traceability.md` 指标记录请求量、样本、百分位与机器配置；未压测不宣称容量达标。 |

## 7. 现场填写与收尾

每一行至少保存：`ID | 链路号 | 前置版本 | 操作时间 | 请求/页面 | HTTP/Protobuf | MySQL/ClickHouse 核对 | 日志 | 结果 PASS/FAIL/BLOCKED/NOT_RUN | 缺陷编号`。结果为 FAIL/BLOCKED 时不要合并到“通过数”。检查 32 个链路号均有实际记录，T00/T33–T38 单独汇总。用例没有条件产生数据时记 BLOCKED 和原因，不能把“返回空数组”当成正确汇总。正式外部通知未实现，相关投递项只能“预期失败处理通过/外部投递 BLOCKED”。

最后删除隔离库中本次 `it-*` 对象时遵守依赖顺序（先关闭规则→删规则/卡片/大盘→删叶→清账户关联），保留必要脱敏证据；若数据库为专用一次性测试实例可由 DBA 在备份核对后整体销毁。`rm` 不用于清理共享数据。恢复 Apollo 原发布版本、通道/限流参数；删除临时 Cookie 与二进制文件（仅确认是本次创建的路径），退出 shell 清除敏感环境变量。当前手册无法替代现场容器/服务实测；所有用例初始状态均为 **NOT_RUN**。
