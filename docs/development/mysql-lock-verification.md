# MySQL 元数据范围锁现场验收

> 初始状态：**NOT_RUN**。仅在隔离副本由 DBA 批准后执行；本文不是通过报告。
> 离线 `MySqlDistributedLockSpec` 只使用 Spock Mock / Stub，不替代真实阻塞、超时与索引验证。

## 1. 升级前置与范围

新库 `05-mysql-schema.sql` 已含 `nc_distributed_lock`；已有库先备份、演练、审批执行
`migrations/2026-10-03-distributed-lock.sql`。确认 InnoDB、主键 `lock_key`、`utf8mb4_bin`；
应用账号具备 SELECT / INSERT / UPDATE 权限。MySQL 锁不增加外键，不使用 GET_LOCK。

当前低频共享元数据写入口统一使用稳定键 `metadata`：平台初始化及配置、账号管理、
组织树/成员/删除、组织资源投影相关的大盘/卡片写入、告警生命周期写入。
锁在校验之前开启事务，主键 upsert 建行后以完整主键 SELECT FOR UPDATE；
锁与 `@Transactional` 共用 `mysqlTransactionManager`（MySQL 主数据源），嵌套 REQUIRED 调用复用连接。
JDBC 查询超时 10 秒，锁事务超时 15 秒；不是整个 HTTP 请求的硬截止时间。
MySQL 阻塞等待还受服务端 `innodb_lock_wait_timeout` 和驱动取消行为约束，需现场验证。

一次性初始化持锁后重新校验状态。互斥不是执行去重，不承诺跨 MySQL / ClickHouse / 外部投递原子性。
各实例的内存桶刷盘/释放不能用本锁全局只执行一次；告警窗口仍在进程内，
跨实例窗口连续性和通知去重**没有因此解决**。锁键稳定，禁止用消息 ID 建无界锁行。

## 2. 两个真正独立的 MySQL 连接

开两个交互式客户端 A、B（不要把密码放命令行），都使用同一隔离库和应用账号。
先在 A 无事务时准备两个测试键，仅为本次验收使用，勿测试共享生产 `metadata`：

```sql
INSERT INTO nc_distributed_lock (lock_key)
VALUES ('it-lock-a'), ('it-lock-b')
ON DUPLICATE KEY UPDATE lock_key = lock_key;
SHOW CREATE TABLE nc_distributed_lock;
EXPLAIN SELECT lock_key FROM nc_distributed_lock WHERE lock_key = 'it-lock-a' FOR UPDATE;
```

证据：主键唯一、`EXPLAIN key=PRIMARY`、等值完整命中单行（rows 估值并非互斥证据）。

### 同键阻塞、提交释放

A 执行后保持事务未结束：

```sql
START TRANSACTION;
SELECT lock_key FROM nc_distributed_lock WHERE lock_key = 'it-lock-a' FOR UPDATE;
```

B 执行：

```sql
SET SESSION innodb_lock_wait_timeout = 5;
START TRANSACTION;
SELECT lock_key FROM nc_distributed_lock WHERE lock_key = 'it-lock-a' FOR UPDATE;
```

B 必须等待；在 5 秒内 A 执行 `COMMIT`，B 才返回。随后 B `ROLLBACK`。
记录连接 ID、开始/返回时间和事务结束时间，不能只凭两条 SELECT 都成功判互斥。

### 异键并行、回滚释放

A 再开启事务持有 `it-lock-a`；B 开启事务查询 `it-lock-b`，应不被 A 阻塞，B 回滚。
B 再查询 `it-lock-a` 应等待，A `ROLLBACK` 后 B 才返回；B 回滚。

### 等待超时、不无锁继续

A 持有 `it-lock-a` 超过 5 秒，B 查询同键应返回锁等待超时（通常错误 1205），
B 显式 `ROLLBACK`，再 A 回滚。SQL 客户端失败不证明应用失败传播正确：
还需在隔离应用中由 A 持有 `metadata`，从新应用发组织重命名等写请求，
核对请求失败、业务行及投影未改变；释放后重新发请求成功。应用实际超时记录为证据。
死锁/驱动超时如无安全注入方法，记 BLOCKED，不在共享库造故障。

## 3. 应用跨实例与回滚

- 两个实例同时初始化同一空平台：只一个成功，另一个在拿锁后重新校验并拒绝；
  平台单行、超管及通道状态一致。不得重置共享平台来测试。
- 两实例并发建大盘/卡片/规则与删除叶子，核对主表和资源投影一致，无悬挂数据。
- 安全故障注入让业务 SQL 失败：整个 MySQL 事务回滚，第二实例随后可获得锁。
- 运行 T33 / T34 验证真实 Spring + MyBatis 同连接、代理顺序及事件传播；
  单测里的 JDBC Mock 复用证据不能标成真实 MyBatis 往返通过。

收尾确认 A/B 无开放事务后，只删除本次键：
`DELETE FROM nc_distributed_lock WHERE lock_key IN ('it-lock-a', 'it-lock-b');`。
不要清理应用 `metadata` 行，不在持锁期间删行。每项记录 PASS / FAIL / BLOCKED / NOT_RUN、
版本、执行人、脱敏 SQL、HTTP、时间与日志。
