# Metric / Heartbeat 部署与兼容说明

1. **先人工执行** `2026-10-02-metric-heartbeat.sql`，再部署后端。SQL 使用 `ADD COLUMN IF NOT EXISTS`
   和 `CREATE TABLE IF NOT EXISTS`，不清空历史数据、不自动执行迁移。
2. 全新环境可直接使用更新的 `../06-clickhouse-schema.sql`。
3. 后端部署后，旧 SDK 仍可上报原 5 项；新 SDK 可以按调用方自行选择的周期调用
   `NeoCat.logJvmHeartbeat()`，不自动安装后台采集任务。
4. 手动 `logHeartbeat(Map<String,String>)` 保留原键 `heapUsed/heapMax/gcCount/gcTime/threads`，
   新键为 `youngUsed/youngCommitted/youngMax`、`oldUsed/oldCommitted/oldMax`、
   `metaspaceUsed/metaspaceCommitted/metaspaceMax` 和 `youngGcCount/youngGcTime`、
   `oldGcCount/oldGcTime`、`fullGcCount/fullGcTime`。bytes、ms、count 分别使用整数。
   未提供、负数或非法数字不上报；显式 `0` 是有效值。

## 历史兼容与可观测边界

- 旧 Heartbeat 桶是 sum，无法反推最后采样；迁移后对应最后值为 NULL。不能用 sum/max
  冒充最后值。GC 按事件时间保留累计采样及重启归零；同一毫秒的冲突采样用较大值作为
  确定性平局规则（仅时间完全相同时），不以最大值代替不同时间的最后采样。
- 旧 Metric 表若本来已有 `value_count`，保留观测次数；否则无法从数值总和恢复次数，
  `value_count=0` 的遗留桶视为缺口。混有不可恢复历史桶的总量不能只报可见部分。
  `value_count_missing` 随后续小时/日聚合传递，避免新数据混入后掩盖旧桶的不确定性。
- 旧标签身份缺少原始结构化标签/归属元数据，筛选保持 `MERGED_OTHER` 缺口。
  metadata 按来源保存上报次数版本，查询不累加重复快照；other 观测次数超过已知合并
  元数据计数时，视为元数据落后，不能用不完整标签候选宣称条件完全可还原。
  普通不含 `%`、`=`、`;` 的标签保留旧身份；含这些字符的新上报使用 `v2:` + Base64URL
  分段身份，不能把旧有歧义串解码并迁移为猜测的标签。
- 新快照按 `(source, series, bucket)` 取最新版本，再汇总来源；整点补刷不重复计数。
  分钟和小时的 source 是写入进程身份，独立进程贡献相加；日/周/月重建为全历史快照，
  共享全局 source，以避免重复调度和重启重建叠加。遗留增量历史重复刷写无法追溯去重。
- 迟到可接收小时按配置保留并补刷，窗口关闭再释放内存；上一日会在小时调度后重建。
  历史接口反映已落库采样，补刷之前可能暂有延迟；没有运行数据库实测，不声称已通过
  ClickHouse 实际执行或在线上报验收。
- count 不用排名表求总量。当前独立标签名额仍为小时内先到先得；严格重新选 TopN 并
  迁移已写数值分布不是本轮新增能力。元数据坚持记录实际归属，不伪造重新排名后的身份。
- 标准 MXBean 无法可靠区分 Old GC / Full GC；自动采样不填 Full GC。未知收集器或
  内存池分项为空；部分 JVM/GC 组合没有年轻代/老年代分项，不填零或编造。

## 验证与上线检查

离线：Maven 测试、HTTP Controller 回归、JDBC 参数/列/版本回归、协议漂移检查和前端
测试/类型检查。上线环境还须执行 SQL、验证重复快照 count 不增长、乱序 Heartbeat 选
最后事件时间、实例不求和、历史/当前小时衔接，以及带分隔符的标签筛选。
浏览器视觉验收由用户执行。

### 本轮验证记录（2026-10-02）

- `mvn -q verify`：backend 96 个测试套件、1246 项测试；client-java 2 个套件、30 项测试，
  无失败、无错误、无跳过，同时通过编译与打包。
- `node scripts/check-protocol-drift.mjs`：单一 proto，两端 19 个生成类逐字节一致。
- `node scripts/check-contract-alignment.mjs`：后端 64 路径、前端 mock 39 路径对齐。
- `node scripts/check-error-codes.mjs`：44 个错误码编号一致。
- 前端 `npm test`：83 项通过；`npm run flow`：47 个检查点通过；`npm run build`：
  类型检查与生产构建通过。
- 已执行手动代码审查及回归修正；JDBC 测试使用替身捕获参数/SQL 和读取映射，
  **不是**真实数据库执行结果。未执行运行数据库迁移，未启动在线采集或浏览器验收。
