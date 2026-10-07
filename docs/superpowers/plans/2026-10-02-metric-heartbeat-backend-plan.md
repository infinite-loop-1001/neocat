# Metric / Heartbeat 实施与验收清单

设计：`../specs/2026-10-02-metric-heartbeat-backend-design.md`，用户已批准最后采样方案。

- [x] 先补失败测试：Heartbeat presence 与最后采样，再实现协议和内存模型。
- [x] Protobuf 保留原编号、扩展 20 项 presence 与客户端标记；接收与指纹兼容旧载荷。
- [x] Java SDK 手动入口扩展，新增显式 MXBean 采样，不自动启动定时器。
- [x] MinuteBucket、AggregatedRow、Roller、内存查询与历史映射传递最后值及事件时间。
- [x] Metric 三接口、严格 JSON 筛选、实际标签归属与版本化无碰撞标签身份。
- [x] Metric 专用来源读取保留来源小时/日，历史与当前小时衔接，unknown/other 不补零。
- [x] ClickHouse schema 与可重复执行增量迁移：value_count、last/time、covered_seconds、
  writer/version、标签 metadata/source，并修复周/月 problem_category 列缺失。
- [x] 快照去重、整点分钟补刷、按接收窗口保留迟到小时、日窗口重建与时区 Date 映射。
- [x] HTTP/JDBC 离线回归与全模块 Maven 测试、协议同源检查；前端测试/flow/构建。
- [x] 手动审查并补来源时间错位、旧观测次数不可恢复、标签碰撞及元数据落后回归。
- [x] 最终 `mvn verify`：backend 1246 项、SDK 30 项通过；前端 83 项和 flow 47 点通过，
  build、协议/接口路径/错误码检查通过。验证记录见部署说明。
- [ ] 真实 ClickHouse 执行迁移及在线采集、重复写入验证（环境无 clickhouse/docker，未执行）。
- [ ] 浏览器功能/视觉验收（由用户完成）。

部署顺序与历史兼容：`../../neocat-technical-design/migrations/2026-10-02-metric-heartbeat.md`。
当前目录不是 Git 仓库，未提交或推送。
