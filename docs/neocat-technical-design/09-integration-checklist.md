# 09｜联调与人工验证清单

> 用途：中间件接通后按本清单逐步验证。所有**未在本机执行过**的项都标了 ⚠️，
> 原因见每项说明。单测不依赖本清单（见 07 文档 §2）。

## 1. 环境前置

| 项 | 要求 | 验证方式 |
|---|---|---|
| JDK | 17 | `java -version` |
| Maven | 3.9+ | `mvn -v` |
| MySQL | 8.0 | `mysql -V` |
| ClickHouse | 24.x | `clickhouse-client --version` |
| Apollo | 2.x（必需） | 不可达、配置缺失或只有本机缓存时启动失败 |
| Node | 18+ | `node -v`（前端） |

本机状态：JDK 17 存在（`~/.sdkman/candidates/java/17.0.15-zulu`），**无 Docker**，
因此 MySQL 与 ClickHouse 未启动。

## 2. 数据库初始化 ⚠️

```bash
# 2.1 MySQL：建库建表 + 初始化数据
mysql -h localhost -u root -p < docs/neocat-technical-design/05-mysql-schema.sql

# 2.2 ClickHouse：建库建表（TTL 随建表语句生效）
clickhouse-client --multiquery < docs/neocat-technical-design/06-clickhouse-schema.sql
```

**验证 SQL 正确性的自检**

```sql
-- MySQL：应返回 1 行；org_count=0；account_count 初始为 0
SELECT * FROM neocat.nc_platform_profile;
SELECT * FROM neocat.nc_channel_config;            -- 应 3 行且 enabled 全为 0
SELECT COUNT(*) AS org_count FROM neocat.nc_org_node;

-- ClickHouse：应返回 10 张表
SHOW TABLES FROM neocat;
```

⚠️ **未验证**：脚本语法按 MySQL 8.0 与 ClickHouse 24.x 规范书写，但从未实际执行。
若报错，最可能的位置：
- `05-mysql-schema.sql` 的初始化 `INSERT ... ON DUPLICATE KEY UPDATE`（处理单行表与三通道初始数据）；
- `06-clickhouse-schema.sql` 的 `arrayMap(i -> sum(arrayElement(...)), range(1, 17))`
  依赖 ClickHouse 的 lambda 支持（24.x 起稳定）。

> 该 MySQL 脚本不创建任何 VIEW，也不使用外键：引用完整性与“叶子是否有资源”判据都在应用层。
> 需要排查时见脚本 §7 的只读示例查询（含投影一致性核对）。

## 3. Apollo 配置 ⚠️

在 Apollo 的 `application` 命名空间填入全部必需运行配置（键见 `07-config-and-testing.md` §1.2），
另含 `server.port`、`spring.datasource.*`、`mybatis.mapper-locations`、
`neocat.clickhouse.url/username/password` 和 `neocat.platform.init.timezone`。
不要使用旧的本地 `application.properties`，也不要在仓库中写入真实凭据。

**验证**：启动日志出现 `RealtimeConsumer 已启动，消费线程数：4，队列容量：65536`。
断开 Apollo 后启动（即使已有客户端缓存）应失败；缺少必需键也应失败。

## 4. 启动顺序

```bash
# 4.1 两端独立构建；不安装父 POM 或协议工件
mvn -f backend/pom.xml clean verify
mvn -f client-java/pom.xml clean verify

# 4.2 用应用 main 启动后端（AppId 由 META-INF/app.properties，Meta 由 -Dapollo.meta/APOLLO_META）
cd backend && mvn spring-boot:run -Dspring-boot.run.jvmArguments="-Dapp.id=neocat -Dapollo.meta=http://<apollo-meta-host>:8080"

# 4.3 启动前端（mock 模式，无需后端）
cd front && npm run dev

# 4.4 启动前端（真实后端）
cd front && VITE_USE_MOCK=false npm run dev
```

**启动自检**

```bash
curl -s localhost:8080/api/platform/init-status        # {"initialized":false}
curl -s -X POST localhost:8080/api/platform/initialize \
  -H 'Content-Type: application/json' \
  -d '{"timezone":"Asia/Shanghai","adminUsername":"root","adminPassword":"NeoCat@2026"}'
```

⚠️ **未验证**：控制器与拦截器已就位，但从未在真实端口上跑过。
首次启动若报 `DataSource` 相关错误，检查 Apollo 命名空间中的数据源键与实际连接。

**4.5 协议同源自检（不需要任何中间件）**

方案 B 下两端各自从仓库根 `proto/` 生成协议类，必须证明它们同源：

```bash
mvn -o -q -f backend/pom.xml -DskipTests package
mvn -o -q -f client-java/pom.xml -DskipTests package
node scripts/check-protocol-drift.mjs
# 期望：结果：协议同源 —— 单一 .proto，两端生成物逐字节一致
```

该脚本同时拦截「有人另存第二份 `.proto`」与「两端生成物不一致」两种情况
（`04-ingest-protocol.md` §2.1）。

## 5. 上报链路验证

### 5.1 SDK 冒烟

```java
ClientConfig config = new ClientConfig() {
    public int queueCapacity() { return 1000; }
};
NeoCat cat = NeoCat.create(config, "order", "10.0.0.8",
        "http://localhost:8080/api/v1/ingest", payload -> {
            // 真实 HTTP 发送：Content-Type: application/x-protobuf
        });
cat.newTransaction("URL", "POST /orders").complete();
cat.logEvent("business", "order-created", "0");
cat.shutdown(2000);
```

### 5.2 手工构造请求（无 SDK 时）

```bash
# 用 protoc 生成 payload（协议事实源在仓库根 proto/）
protoc --encode=neocat.ingest.v1.IngestRequest \
  -I proto \
  proto/neocat/ingest/v1/ingest.proto > /tmp/tree.bin <<'EOF'
protocol_version: "1.0"
trees {
  service_name: "order" instance_id: "10.0.0.8"
  message_id: "m-1" root_message_id: "m-1"
  tree_timestamp: 1790000000000
  nodes {
    node_id: "n-1" kind: TRANSACTION category: "URL"
    name: "POST /orders" status: "0"
    timestamp: 1790000000000 duration_ms: 42
  }
}
EOF

curl -s -X POST localhost:8080/api/v1/ingest \
  -H 'Content-Type: application/x-protobuf' --data-binary @/tmp/tree.bin \
  -o /tmp/resp.bin -w '%{http_code}\n'
# 期望 202

protoc --decode=neocat.ingest.v1.IngestResponse \
  -I proto \
  proto/neocat/ingest/v1/ingest.proto < /tmp/resp.bin
# 期望 status: ACCEPTED, accepted_trees: 1
```

### 5.3 逐条核对上报语义（PRD 02 §11）

| 验收项 | 操作 | 期望 |
|---|---|---|
| 幂等 | 同一 payload 发两次 | 第二次 `status: DUPLICATE`，报表总量不变 |
| ID 冲突 | 同 `message_id` 改 `duration_ms` 再发 | HTTP 409，`nc_quality_event` 出现 `ID_CONFLICT` |
| 迟到拒绝 | `tree_timestamp` 设为 3 小时前 | HTTP 422，`TREE_EXPIRED`，不入任何分析域 |
| 过载丢弃 | 把队列容量调到 1 并连续上报 | HTTP 202 `QUEUE_FULL`，**但服务仍出现在 `/api/services`** |
| 单域隔离 | 临时让某分析器抛异常 | 其他域数据仍写入，`nc_quality_event` 出现 `DOMAIN_FAILURE` |

⚠️ **未验证**：以上全部依赖真实端口与中间件。幂等、迟到、过载的判断逻辑
已由 60+ 个 Spock 规格覆盖，但**端到端链路**未跑过。

## 6. 报表链路验证

```bash
# 上报后等待一个完整分钟（分钟落库 cron 在每分钟 :05）
sleep 70

curl -s 'localhost:8080/api/services?kind=TRANSACTION'
# 期望包含 order

curl -s 'localhost:8080/api/reports/transaction/types?service=order&range=RECENT_1H'
# 期望包含 type=URL 且 total>0

curl -s 'localhost:8080/api/reports/series?service=order&kind=TRANSACTION&type=URL&name=POST%20/orders&stat=HITS'
# 期望 points 数组非空，且不存在 value:0 的伪缺口
```

**逐条核对报表口径（PRD 03 §12）**

| 验收项 | 检查点 |
|---|---|
| 趋势默认 Hits | 不传 `stat` 时返回 `stat: HITS` |
| 缺数不为 0 | 未上报时段应出现 `value: null` + `quality: NO_DATA` |
| 真无调用为 0 | 上报了但次数为 0 的时段应为 `value: 0` + `quality: ZERO` |
| 当前小时 QPS | 当前小时桶的 QPS 分母为「整点至现在的秒数」，不是 3600 |
| 周趋势 1 小时/点 | `range=THIS_WEEK` 时 `bucketSeconds == 3600` |

⚠️ **未验证**：需要上报数据 + ClickHouse 落库后才有意义。

## 7. 前端验收

```bash
cd front

# 7.1 mock 模式（默认）：无需后端即可演示全部流程
npm run dev
# 用 root / NeoCat@2026 登录；bob 首次登录会强制改密

# 7.2 动线自检（可执行证据）
npm run flow          # 期望：动线自检通过：40 个检查点

# 7.3 构建
npm run build         # 期望：0 错误

# 7.4 真实后端联调 ⚠️
VITE_USE_MOCK=false npm run dev
```

**逐条核对前端主链路**

| 链路 | 步骤 |
|---|---|
| 服务诊断 | 服务列表 → transaction → 点 Type → 点 Name → 趋势 → 机器勾选 → 取样「查看 Trace」 |
| 组织大盘 | 组织大盘 → 选叶子 → 建大盘 → 建卡片 → 输入非法公式（如 `tp99 + hits`）→ 应报「耗时与次数不能直接相加减」 |
| 组织告警 | 卡片「一键创建组织告警」→ 切换试算场景三次 → 应依次显示会触发/不会触发/数据不足 |
| 管理 | 账号管理 → 建号 → 重置密码 → 授予管理员；组织树 → 删除预览 → 输入错误名称应被拒 |
| Trace | 取样中「原始树已过期」的行应无「查看 Trace」按钮 |

⚠️ **未验证**：7.4 需要后端与中间件就绪。

## 8. 定时任务验证

```bash
# 观察日志：分钟落库每分钟出现一次
tail -f backend/logs/spring.log | grep -E "分钟落库|小时聚合|留存清理"
```

| 任务 | 期望日志 | 时间 |
|---|---|---|
| 分钟落库 | 仅在有数据时输出 debug | 每分钟 :05 |
| 小时聚合 | `小时聚合完成，写入 N 行` | 整点后 :02 |
| 留存清理 | `留存清理完成：分钟桶 A 行、小时桶 B 行、日周月 C 行` | 每日 02:00 |
| 告警判定 | 触发时 `规则 X（name）在 T 触发，通知 N 条` | 每分钟 :05 |

⚠️ **未验证**：cron 表达式的触发依赖真实运行时。

## 9. 已知未实现 / 有意简化

| 项 | 状态 | 说明 |
|---|---|---|
| 通知通道真实投递 | 接口就位，无 SMTP/Webhook 实现 | `Notifier` 由装配层注入，需你提供凭据 |
| 告警窗口跨重启保持 | 有意走内存 | 与「启用后重建窗口」语义一致，不产生错误告警；表结构已就绪 |
| ClickHouse 建表自动化 | 手工执行 SQL | 未做 Flyway 等迁移工具 |
| Apollo 命名空间自动创建 | 手工配置 | 未接入 Apollo 管理 API |
| 模块边界按类粒度收窄 | 按包粒度声明 | 包职责已内聚，必要时可细化 |

## 10. 一句话总结

**单测层（1192 + 25）与前端 mock 演示层已完全可验证，不需要任何中间件。**
中间件联调所需的全部代码（控制器、Mapper、DAO、调度器）已就位，
但**从未在真实端口上运行过** —— 上述 5–8 节的检查项就是为这次运行准备的。
