# NeoCat 异机中间件 Docker 部署与接入手册

> 适用：中间件服务器 Linux + Docker，应用服务器运行 Spring Boot/Vite。优先复用**已有 MySQL 与 Apollo**，只新建 ClickHouse；后文提供新环境逐个 `docker run` 部署 MySQL/Apollo 的可选路径。**不运行 docker compose**。本文是操作说明，尚无真实服务器执行记录。
>
> 来源：`backend/src/main/java/com/neocat/common/config/impl/ApolloConfigGuard.java`、`backend/src/main/java/com/neocat/trace/infra/clickhouse/ClickHouseConnection.java`、`docs/neocat-technical-design/{05-mysql-schema.sql,06-clickhouse-schema.sql}`；Apollo 独立容器用法见[官方部署指南](https://github.com/apolloconfig/apollo/blob/master/docs/en/deployment/distributed-deployment-guide.md)及[2.4.0 发布说明](https://github.com/apolloconfig/apollo/releases/tag/v2.4.0)。

## 0. 环境变量与安全边界

在两台机器上记录：`MID_HOST`（中间件服务器**应用机可达**的内网 IP/DNS）、`APP_HOST`、`MYSQL_HOST:3306`、Apollo Meta URL、ClickHouse `8123`（HTTP JDBC）及运维终端地址。不要将示例尖括号照搬。开启主机防火墙：3306/8123/8080 只放行应用机和授权运维机；8090 仅 Apollo 内部/管理网络、8070 仅管理员，9000（ClickHouse native）无需对应用机开放。跨非受信网络使用 TLS/VPN/隧道；示例明文 HTTP 仅用于隔离联调网。检查服务器 Docker、磁盘空间、时间同步、架构及镜像平台：`docker version`、`df -h`、`date -u`。Docker 已有容器先 `docker ps -a`，**禁止覆盖旧容器或卷**。

下面命令中的密码通过交互式提示读取（当前 shell 内存，退出后 `unset`），但 Docker 环境变量仍可能被有权限执行 `docker inspect` 的人读到；限制 docker 组及运维权限。生产建议 secrets 管理，不要把凭据写进仓库、命令历史、截图或工单。联调使用独立 NeoCat 数据库/账号，先备份已有实例。

## 1. 优先路径：核查现有 MySQL 与 Apollo

1. 在中间件机查现状：`docker ps --format 'table {{.Names}}\t{{.Image}}\t{{.Ports}}'`（若宿主机安装则查服务状态）；记录 MySQL 8.0、Apollo Config/Admin/Portal 版本和 DB 名称。不要为了 NeoCat 重建已有 ApolloConfigDB/ApolloPortalDB。
2. 在应用机检查连通：`mysql -h <MYSQL_HOST> -P 3306 -u <neocat_user> -p -e 'SELECT VERSION(),CURRENT_USER()'`（账号创建见 §3）；`curl --fail --show-error 'http://<MID_HOST>:8080/services/config?appId=neocat'`。Apollo 响应必须是数组且至少一个 `homepageUrl`，**在应用机再 curl 该 homepageUrl 的 `/configs/neocat/default/application`**；这与 Apollo 客户端的真实发现链路一致。404、空配置或容器私网 IP 都会导致启动校验失败。
3. 若现有 Apollo 版本不是 2.4.0，先确认其 API 兼容；升级须遵循官方版本迁移 SQL，先备份再按 Config → Admin → Portal 顺序升级，不能直接换镜像。Portal 中确认存在 `neocat` AppId、`default` cluster、已发布的 `application` namespace，环境对应 Portal 的 Meta。**未发布草稿不算配置可读**。
4. Apollo 返回的 `homepageUrl` 如指向 `localhost`/Docker 172.* 内网，应修正其服务注册地址/发现配置为应用机可达的中间件内网地址，并重试上述两次 curl；不要靠本地 Apollo 缓存绕过在线检查。

## 2. ClickHouse 24.x：单容器安装（在中间件机）

以下是全新、隔离联调环境；选定并核实发行版 24.x 具体标签后固定（示例 `24.8`，上线前锁定镜像 digest）。已有 ClickHouse 先核查版本、备份和 schema，**不要重新创建容器**。本机已有 Docker 网络 `infra-net`，因此容器通过 `--network infra-net` 加入该网络。

```bash
docker pull clickhouse/clickhouse-server:24.8
docker volume create neocat-ch-data
docker volume create neocat-ch-logs
read -r -s -p 'ClickHouse password: ' CH_PASSWORD; echo
docker run -d --name neocat-ch --restart unless-stopped \
  --network infra-net \
  --ulimit nofile=262144:262144 \
  -p 8123:8123 -p 127.0.0.1:9000:9000 \
  -e CLICKHOUSE_DB=neocat -e CLICKHOUSE_USER=neocat -e CLICKHOUSE_PASSWORD="$CH_PASSWORD" \
  -v neocat-ch-data:/var/lib/clickhouse -v neocat-ch-logs:/var/log/clickhouse-server \
  clickhouse/clickhouse-server:24.8
docker logs --tail 80 neocat-ch
docker exec -e CLICKHOUSE_PASSWORD="$CH_PASSWORD" neocat-ch \
  clickhouse-client --user neocat --password "$CH_PASSWORD" --query 'SELECT version(), currentDatabase()'
```

核对网络归属：

```bash
docker inspect -f '{{json .NetworkSettings.Networks}}' neocat-ch
# 结果中应包含 infra-net，并显示容器内网 IP
docker network inspect infra-net
```

如果 NeoCat 应用也作为容器加入 `infra-net`，容器间直接使用 Docker DNS 和容器端口：`jdbc:clickhouse://neocat-ch:8123/neocat`；此时应用配置中的 `neocat.clickhouse.url` 不要写 `localhost`。如果 NeoCat 应用运行在另一台服务器，或运行在本机但不在 `infra-net`，继续使用中间件宿主机可达地址：`jdbc:clickhouse://<MID_HOST>:8123/neocat`，通过 `-p 8123:8123` 从宿主机暴露 HTTP 端口。使用本机安装的 ClickHouse 客户端或 `curl -u 'neocat:<password>' --data-binary 'SELECT version()' http://<MID_HOST>:8123/` 检查；`<password>` 写在命令行会进入进程列表/历史，仅供隔离测试；更安全地从受限权限文件读取或使用客户端交互认证。

如果应用容器当前只在 `compose_infra-net`，它**不能自动解析** `neocat-ch`；应将应用容器额外连接到 `infra-net`（确认不会破坏现有网络后执行 `docker network connect infra-net <应用容器名>`），或使用宿主机地址方式。不要把 `compose_infra-net` 误写成 `infra-net`，也不要创建同名替代网络。

**仅新库**（从仓库根目录，在中间件机可访问 SQL 文件时执行；`docker exec -i` 必须带 `-i`）：

```bash
docker exec -i neocat-ch clickhouse-client --user neocat --password "$CH_PASSWORD" \
  --multiquery < docs/neocat-technical-design/06-clickhouse-schema.sql
docker exec neocat-ch clickhouse-client --user neocat --password "$CH_PASSWORD" \
  --query 'SHOW TABLES FROM neocat'
```

期望十一张表（`nc_minute_bucket`, `nc_hour_bucket`, `nc_day_bucket`, `nc_week_bucket`, `nc_month_bucket`, `nc_metric_hour_rank`, **`nc_metric_label_metadata`**, `nc_raw_tree`, `nc_trace_relation`, `nc_quality_event`, `nc_ingest_stat`）。**已有库**走下述增量升级，不重放新库脚本；不可认为 `IF NOT EXISTS` 就会补列/TTL。SQL 执行错误即停止、保留日志，先核对驱动与数据库版本。

### 2.1 已有 ClickHouse：Metric count / Heartbeat 增量升级

升级脚本：[2026-10-02-metric-heartbeat.sql](../neocat-technical-design/migrations/2026-10-02-metric-heartbeat.sql)。此脚本已编写，**尚未在真实 ClickHouse 执行验收**；可重复执行不等于覆盖所有历史版本差异。先在副本核对基础表、引擎、排序键及 `06-clickhouse-schema.sql` 的其他差异，缺基础表/旧字段时停止并交 DBA 审查，不自行猜测补齐。

1. 记录旧应用/SDK/前端产物及 SHA256、Apollo 发布版本、十一表的 `SHOW CREATE TABLE`，备份数据并演练恢复。暂停接收上报和异步消费/刷新/滚动写入，等待在途任务结束，再做一致备份；业务侧 sender 的积压、丢弃与重试须记录。
2. 副本演练通过且审批后，在**升级后端启动前**执行（以下仅是操作命令，本次未执行）：

   ```bash
   docker exec -i neocat-ch clickhouse-client --user neocat --password "$CH_PASSWORD" \
     --multiquery < docs/neocat-technical-design/migrations/2026-10-02-metric-heartbeat.sql
   ```

3. 保存执行结果，逐表只读复核五级桶 `nc_{minute,hour,day,week,month}_bucket` 的 `value_count`、`value_count_missing`、`value_last`、`value_last_time`、`snapshot_source`、`snapshot_version`；小时及以上的 `covered_seconds`，周/月的 `problem_category`，以及新 `nc_metric_label_metadata` 的 `labels_json/merged/version/source`。用 `SHOW CREATE TABLE`/`DESCRIBE TABLE` 与脚本比对类型、默认值、TTL，不只数表。
4. 发布兼容后端并完成 Apollo 两跳预检、生产装配和读写冒烟，再更新 SDK/前端。恢复一小组隔离实例，执行联调 T20/T21 与 T33/T35，确认重复刷新不会重复累计、跨小时来源和标签筛选正确后才扩大流量。跨桶验证要等真实刷新，HTTP 202 不代表落库。

历史数据边界：旧 Heartbeat 仅存 sum，不能还原事件时间最后采样，保持 `null` 缺口；旧 Metric 缺观测次数或标签归属时不伪造 count/筛选结果，不把 `value_count` 回填成 sum。全量 count 包含 `other` 一次，筛选同键 OR、异键 AND；`other` 导致无法恢复时 `value=null / quality=MERGED_OTHER`，无采集 `NO_DATA`，未发生窗口 `NOT_OCCURRED`，均不补零。

### 2.2 SDK 的 Heartbeat 采集边界

`NeoCat.logJvmHeartbeat()` 是**显式采样方法，不安装定时器**；调用方按业务生命周期安排采样及关闭/flush，并注入真实 `MessageSender`（仓库无内置 HTTP sender）。标准 MXBean sampler 对已识别内存池/收集器采样；未知、不支持或 max=-1 保持字段缺失，不能补零。旧 GC 不等于 Full GC，默认 sampler 不猜测 Full GC；可由具备可靠来源的调用方通过 `logHeartbeat(Map<String,String>)` 提供 `fullGcCount/fullGcTime`。

固定 20 项编目：heap-used/max、gc-count/time、threads；young/old/metaspace 各 used/committed/max（9 项）；young/old/full 各 gc-count/time（6 项）。内存单位 bytes，GC time 毫秒、count 累计次数；每个 JVM 独立曲线，Gauge 和累计 GC 按事件时间取桶内最后采样，不跨 JVM 求和、不把累计计数当速率、无环比。编目存在不代表所有 JVM 都能采齐 20 项。令牌/会话鉴权冲突仍是 SDK 直连阻断项（§5、联调 T11–T12），不能以带 Cookie 的人工请求替代 SDK 验收。

## 3. 可选：新环境独立 MySQL 容器

已有 MySQL 跳过容器创建；在现有实例中由 DBA 建独立 `neocat` 库和最小权限账号并备份。新环境：

```bash
docker pull mysql:8.0
docker volume create neocat-mysql-data
read -r -s -p 'MySQL root password: ' MYSQL_ROOT_PASSWORD; echo
docker run -d --name neocat-mysql --restart unless-stopped \
  -p 3306:3306 -v neocat-mysql-data:/var/lib/mysql \
  -e MYSQL_ROOT_PASSWORD="$MYSQL_ROOT_PASSWORD" mysql:8.0
docker logs --tail 80 neocat-mysql
docker exec -it neocat-mysql mysql -u root -p -e 'SELECT VERSION()'
```

新库先用 DBA 账号执行仓库根目录的全新脚本：`mysql -h <MYSQL_HOST> -u <DBA> -p < docs/neocat-technical-design/05-mysql-schema.sql`；脚本会建库建表。之后 DBA 根据应用机来源创建账号，示意：`CREATE USER 'neocat'@'<APP_HOST或受控网段>' IDENTIFIED BY '<随机密码>'; GRANT SELECT,INSERT,UPDATE,DELETE ON neocat.* TO 'neocat'@'<APP_HOST或受控网段>';`（两个 SQL 在 DBA 的 mysql 交互界面执行，不把密码放 shell 历史）。若需应用运行期调整 ClickHouse TTL，与 MySQL 账号权限无关，另由数据库管理员控制。验证 `SELECT initialized,timezone FROM neocat.nc_platform_profile;`（一行 0）及 `SELECT channel,enabled FROM neocat.nc_channel_config;`（三行全 0）。SQL 文件有**非幂等 `CREATE TABLE`**，切勿对已有 NeoCat 库重放。

### 已有 NeoCat 库的迁移门槛

**本轮新增的 MySQL 锁表**：升级到使用 `@MySqlLocked` 的应用之前，审批并在隔离副本演练
[`migrations/2026-10-03-distributed-lock.sql`](migrations/2026-10-03-distributed-lock.sql)。选择 NeoCat 业务库执行，
随后 `SHOW CREATE TABLE nc_distributed_lock` 确认 InnoDB、`lock_key` 完整主键、`utf8mb4_bin` 排序规则。
该脚本仅新增锁表，**不能补齐其他已有库差异**；`IF NOT EXISTS` 也不能证明已有同名表结构正确。
应用账号需对此表有 SELECT / INSERT / UPDATE 权限。当前现场状态 **NOT_RUN**。
回退旧应用时保留此空闲表即可，不为回退而删除业务数据；删除表必须在所有新应用停机后单独审批。
真实双连接验证见 [锁验收步骤](mysql-lock-verification.md)，未验证前不得宣称跨实例互斥已经通过。

停止应用写入、备份并在副本演练。逐表 `SHOW CREATE TABLE`/`SHOW COLUMNS` 与 `05-mysql-schema.sql` 对比：新增告警规则 `target_metric_name/target_metric_labels/formula_stats/channels` 等列、`nc_org_resource` 投影表及其他差异必须有单独审批的增量 DDL。旧 `channels` 应先从 `nc_alert_recipient.channel` 按规则去重回填；无收件人的旧规则**无法推断原通道**，人工核对后才允许加 NOT NULL。参照 `05-mysql-schema.sql` 第 262–270 行同步回填投影，并核对每个组织大盘数、卡片数、组织规则数与源表一致。

**外键变更（本次 SQL 已改为不使用外键）**：新脚本删除了全部 14 个 `FOREIGN KEY`；应用改为在同一事务内显式按依赖顺序级联删除。已有库若仍存在旧外键，必须在增量迁移中一并移除，否则版本并行期行为不一致。先按以下只读语句列出待删约束，再逐条 `DROP FOREIGN KEY`（约束名以实际输出为准，不要照抄示例）：

```sql
SELECT TABLE_NAME, CONSTRAINT_NAME, REFERENCED_TABLE_NAME
FROM information_schema.KEY_COLUMN_USAGE
WHERE TABLE_SCHEMA = 'neocat' AND REFERENCED_TABLE_NAME IS NOT NULL;
-- 逐条形如（约束名取自上一查询，先备份并核对后执行）：
-- ALTER TABLE nc_card DROP FOREIGN KEY fk_nc_card_dashboard;
```

`DROP FOREIGN KEY` 只删约束、保留索引与数据；不要用 `DROP INDEX`，否则会连带丢失查询用的索引。迁移完成后必须复验：`information_schema` 查询返回空集，且「删除大盘要删掉其卡片与阈值线、删除规则要删掉其条件/收件人/窗口点」在副本上实测无悬挂行——新版代码不再依赖数据库级联，**仅删约束而不升级应用会留下悬挂数据**。**本仓库尚无已验证的增量迁移脚本**；没有经过副本验证和回滚方案就停止，不把新库建表 SQL 冒充迁移。

## 4. 可选：新环境 Apollo 2.4.0 三个独立容器

已有 Apollo 则跳过。新环境先下载并核对 [Apollo 2.4.0 MySQL 初始化 SQL](https://github.com/apolloconfig/apollo/tree/v2.4.0/scripts/sql/profiles/mysql-default) 中的 `apolloconfigdb.sql` 和 `apolloportaldb.sql`，仅向**空库**分别导入；脚本自带建库语句，应先确认库名与当前实例无冲突。可用 `curl -fL -o apolloconfigdb.sql https://raw.githubusercontent.com/apolloconfig/apollo/v2.4.0/scripts/sql/profiles/mysql-default/apolloconfigdb.sql` 和相同目录的 `apolloportaldb.sql` 下载，经 DBA 审阅后 `mysql -h <MYSQL_HOST> -u <DBA> -p < apolloconfigdb.sql`、`mysql -h <MYSQL_HOST> -u <DBA> -p < apolloportaldb.sql`；再创建只授权对应库的 Apollo 账号。不要与 NeoCat 库混用。Apollo 旧库升级需按发行说明增量 SQL，绝不覆盖。下面 3 个容器使用宿主机可达 MySQL 地址，不使用容器 `localhost`；先运行 `mkdir -p /srv/apollo/{config,admin,portal}/logs`，并为容器账号开放写权限。

```bash
read -r -s -p 'Apollo DB password: ' APOLLO_DB_PASSWORD; echo
export MID_HOST='<中间件内网IP或DNS>' MYSQL_HOST='<MySQL内网IP或DNS>'
docker pull apolloconfig/apollo-configservice:2.4.0
docker pull apolloconfig/apollo-adminservice:2.4.0
docker pull apolloconfig/apollo-portal:2.4.0
docker run -d --name apollo-configservice --restart unless-stopped -p 8080:8080 \
  -e SPRING_DATASOURCE_URL="jdbc:mysql://${MYSQL_HOST}:3306/ApolloConfigDB?characterEncoding=utf8" \
  -e SPRING_DATASOURCE_USERNAME=apollo -e SPRING_DATASOURCE_PASSWORD="$APOLLO_DB_PASSWORD" \
  -e EUREKA_INSTANCE_HOME_PAGE_URL="http://${MID_HOST}:8080" \
  -v /srv/apollo/config/logs:/opt/logs apolloconfig/apollo-configservice:2.4.0
docker logs --tail 80 apollo-configservice
docker run -d --name apollo-adminservice --restart unless-stopped -p 8090:8090 \
  -e SPRING_DATASOURCE_URL="jdbc:mysql://${MYSQL_HOST}:3306/ApolloConfigDB?characterEncoding=utf8" \
  -e SPRING_DATASOURCE_USERNAME=apollo -e SPRING_DATASOURCE_PASSWORD="$APOLLO_DB_PASSWORD" \
  -e EUREKA_INSTANCE_HOME_PAGE_URL="http://${MID_HOST}:8090" \
  -v /srv/apollo/admin/logs:/opt/logs apolloconfig/apollo-adminservice:2.4.0
docker logs --tail 80 apollo-adminservice
docker run -d --name apollo-portal --restart unless-stopped -p 8070:8070 \
  -e SPRING_DATASOURCE_URL="jdbc:mysql://${MYSQL_HOST}:3306/ApolloPortalDB?characterEncoding=utf8" \
  -e SPRING_DATASOURCE_USERNAME=apollo -e SPRING_DATASOURCE_PASSWORD="$APOLLO_DB_PASSWORD" \
  -e APOLLO_PORTAL_ENVS=dev -e DEV_META="http://${MID_HOST}:8080" \
  -v /srv/apollo/portal/logs:/opt/logs apolloconfig/apollo-portal:2.4.0
docker logs --tail 80 apollo-portal
```

> 上述 `-e ...PASSWORD` 值仍可被 Docker 管理员查看；严格环境改用密钥注入。`/srv/apollo/...` 需先由运维创建、授权。Apollo 2.4 默认 database-discovery；已有旧 Eureka 配置如需兼容，应按其实际 profile/`ServerConfig` 调整，**不要直接复用示例注册地址**。唯一通过判据：应用机调用 `/services/config?appId=neocat` 返回可达 `homepageUrl`，继而 `/configs/neocat/default/application` 返回已发布配置。

## 5. Apollo 中发布 NeoCat 配置

Portal `http://<MID_HOST>:8070` → `dev` → AppId `neocat` → `default` → `application` namespace，逐项新增、**发布**。下方仅展示非敏感示例值；敏感连接信息通过 Portal 受控填入，禁止提交到代码库。`neocat.clickhouse.password` 可为空字符串，但键必须存在；MySQL 密码不能空。全部运行参数**必填**，不是“有默认就可以省略”：

```properties
server.port=8080
spring.datasource.url=jdbc:mysql://<MYSQL_HOST>:3306/neocat?useUnicode=true&characterEncoding=utf8&serverTimezone=UTC
spring.datasource.username=<NEOCAT_DB_USER>
spring.datasource.password=<NEOCAT_DB_PASSWORD>
mybatis.mapper-locations=classpath*:mapper/**/*.xml
neocat.clickhouse.url=jdbc:clickhouse://<MID_HOST>:8123/neocat
neocat.clickhouse.username=neocat
neocat.clickhouse.password=<CH_PASSWORD>
neocat.platform.init.timezone=Asia/Shanghai
neocat.ingest.queue.capacity=65536
neocat.ingest.consumer-threads=4
neocat.ingest.batch-size=200
neocat.ingest.batch-timeout-ms=200
neocat.ingest.max-trees-per-batch=200
neocat.ingest.max-batch-bytes=1048576
neocat.ingest.max-nodes-per-tree=3000
neocat.ingest.idempotency-window-minutes=120
neocat.ingest.auth-token=<随机生成的非空测试令牌；当前服务端尚未校验此头，见联调手册 T12>
neocat.ingest.accept-late-hours=2
neocat.analysis.analyzer-timeout-ms=5000
neocat.report.exact-values.max=200
neocat.report.distribution-buckets=16
neocat.report.minute.retention-days=30
neocat.report.hour.retention-days=30
neocat.report.long-term.retention-months=13
neocat.report.minute-flush-delay-seconds=5
neocat.report.bucket-cache-seconds=60
neocat.metric.top-n=1000
neocat.trace.retention-days=7
neocat.trace.sample-rate=1.0
neocat.trace.sample-rows=30
neocat.alert.evaluate-delay-seconds=5
neocat.alert.notify-timeout-ms=3000
neocat.alert.dedup-per-minute=true
neocat.query.max-buckets=2000
neocat.query.max-instances-topn=20
neocat.heartbeat.topn=10
```

请以 `backend/src/main/java/com/neocat/common/config/impl/ApolloConfigGuard.java` 的 `FRAMEWORK_KEYS` 与动态配置类上的 `@ApolloStaticValue` 注解为最终验收键集；配置其他属性不取代这些项。`neocat.ingest.auth-token` 仍要求非空，但 `SessionWebConfiguration` 对 `/api/v1/ingest` 使用登录 Cookie 拦截，`IngestController` 未校验令牌，填令牌并不等于 SDK 认证可用。见联调手册 T11–T12，必须修复并复测才能开放给 SDK。`server.port=8080` 是**应用机**端口，与中间件机 Apollo Config 的 8080 不冲突。校验发现 URL 和配置（**会返回敏感配置，勿把原始响应贴进日志或工单**）：使用受限终端读取，检查 `configurations` 键及必需键是否齐全，禁止公开输出密码。后端 `NeoCatApplication.main` 由 Apollo 客户端加载配置，启动后由 `ApolloConfigGuard` 校验必需键，缺失或非法即拒绝启动。

## 6. 应用构建、启动与前端同源部署

在仓库根使用 JDK 17+、Maven 构建（Node/npm 用于前端）。根 POM 已删除；先分别执行 `mvn -f backend/pom.xml clean verify`、`mvn -f client-java/pom.xml clean verify` 及三个契约检查脚本；离线单测不连接运行数据库。生产 jar 构建显式执行固定版本 `repackage`，不依赖 POM 隐式绑定：

```bash
mvn -f backend/pom.xml -DskipTests package org.springframework.boot:spring-boot-maven-plugin:3.3.13:repackage
unzip -p backend/target/neocat-backend-0.1.0-SNAPSHOT.jar META-INF/MANIFEST.MF
sha256sum backend/target/neocat-backend-0.1.0-SNAPSHOT.jar
java -Dapp.id=neocat -Dapollo.meta=http://<APOLLO_META>:8080 \
  -jar backend/target/neocat-backend-0.1.0-SNAPSHOT.jar
```

已在本地验证上述打包命令：manifest 含 `Main-Class: org.springframework.boot.loader.launch.JarLauncher`、`Start-Class: com.neocat.NeoCatApplication`，含 `BOOT-INF/classes` 与依赖库。**普通 `mvn package` 不保证可执行 Boot jar**；上线产物必须逐次核对 manifest 和校验和。此打包证据不代表真实 Apollo/数据库启动通过。macOS 校验可用 `shasum -a 256`。开发启动替代路径：在 `backend` 目录运行 `mvn org.springframework.boot:spring-boot-maven-plugin:3.3.13:run -Dspring-boot.run.jvmArguments="-Dapp.id=neocat -Dapollo.meta=http://<APOLLO_META>:8080"`。两条路径都由 Apollo 客户端加载配置，启动后由 `ApolloConfigGuard` 校验。

另开终端检查 `/api/platform/init-status`；未初始化才执行一次性初始化。已有数据为 true 时不要重复初始化。运行参数必须来自已发布 Apollo，禁止把包含密码的配置响应或日志贴入工单。

前端构建必须关闭 mock（在 `front` 目录执行）：

```bash
npm ci
VITE_USE_MOCK=false npm run build
```

将 `front/dist` 发布到应用服务器静态目录，通过同源 HTTPS 反向代理暴露页面与 `/api`。Nginx 示例片段（合并到已有受控 `server`，TLS、域名、权限由运维配置；不覆盖已有站点）：

```nginx
root /srv/neocat/front;
location /api/ {
    proxy_pass http://127.0.0.1:8080;
    proxy_set_header Host $host;
    proxy_set_header X-Forwarded-Proto $scheme;
    proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;
}
location / { try_files $uri $uri/ /index.html; }
```

后端不在同机时改为受控内网地址。`proxy_pass` 此处**不带末尾 `/`**，保留 `/api/...` 路径。DevTools 核对 `/api/platform/init-status` 是后端 JSON，而非静态 HTML/MSW；登录 Cookie 同源并复测刷新路由。当前 Vite 5173 没有 `/api` 代理，直接 `VITE_USE_MOCK=false npm run dev` 不能完成真实联调，需同源代理或审批后的开发代理，见联调手册 §1。

### 6.1 验收与回退

- 上线前完成 T00、T11–T23、T33–T37 的真实记录；特别是 Metric 新 `/metrics`、`/labels`、`/count` 路径和 Heartbeat 20 项、实例隔离、缺口语义。SDK 令牌认证、HTTP sender 和外部通知的阻断项未消除前，不能宣布整套系统生产就绪。
- 回退先暂停写入，保留失败产物、时间窗、schema 与脱敏证据；恢复已验证兼容的应用/SDK/前端及 Apollo 发布版本。增量脚本没有 DROP/逆向脚本，不为回退盲删新表/列，也不假设旧版本一定能读新 schema；必须在副本验证兼容，否则用演练过的备份恢复流程并明确升级后数据的 RPO/损失窗口。
- 数据恢复、容器重启和迁移由运维批准后现场执行。本次仅改代码结构和操作文档，**未迁移运行库、未执行服务器部署或回退**。

## 7. 复验、持久化、备份和排障

- 应用机分别测试 TCP 连通、MySQL `SELECT 1`、ClickHouse `SELECT version()`、Apollo 两跳在线发现；再启动后端。`docker ps` 只说明进程未退出，**不等于 SQL 语义和 Bean 装配成功**。
- 隔离库中创建一条业务记录、重启对应容器 `docker restart neocat-ch`（已有 MySQL/Apollo 未经授权不可重启）、重查记录和 Apollo 发布项；只读核对后再恢复业务测试。数据卷与日志目录分开，定期做 MySQL 逻辑备份、ClickHouse 快照/备份、Apollo 两库备份，演练恢复后才视为可恢复。不要只备份容器镜像。
- 故障定位：Apollo 第一跳失败→Meta/防火墙；第二跳失败→`homepageUrl` 注册网卡/发布；MySQL 拒绝→授权来源、库名/字符集；ClickHouse HTTP 401→账号密码；Mapper 错→`classpath*:mapper/**/*.xml` 与 schema 列差异；TTL/聚合错误→24.x 实际 SQL 核验。敏感日志脱敏后留存。
- 文档不证明生产可用；见 `integration-test-runbook.md` 完成全部真实联调、回滚和异常项目记录。离场执行 `unset CH_PASSWORD MYSQL_ROOT_PASSWORD APOLLO_DB_PASSWORD`，运维密钥轮换按组织流程处理。
