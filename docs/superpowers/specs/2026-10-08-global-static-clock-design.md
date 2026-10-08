# 全局静态时钟简化

用户明确要求：单测主动设置时钟，不再做隔离，整个后端共用静态时间入口。
后续进一步要求：不要提供 set 时钟的方法，单测直接改内部静态字段。
本设计取代此前 static-time-domain-config 设计中 ThreadLocal / Scope / TimeFixture 的要求。

## 实现边界

- TimeProvider 只保留一个 private static volatile Clock，默认系统 UTC；now、millis、
  delayedMinuteStart 都使用它。不提供 setClock 或任何面向生产的设置入口。
- Groovy 单测直接给该字段赋值：`TimeProvider.clock = ...`；结束后在 cleanup / finally
  赋回 `Clock.systemUTC()`。这是单测显式写入，不是作用域或隔离机制。
- 删除 OVERRIDE、Scope、override、TimeFixture、TimeScopedSpecification 与 setClock。
  不提供 ThreadLocal、线程归属、嵌套、AutoCloseable 或自动恢复。所有线程共享时钟，
  修改时钟的测试继续串行执行。
- 保留生产静态取时和已有分钟点计算、动态配置与业务断言。不改 SDK 单调计时。
- 更新当前约束文档；历史设计与过去验收结果保留，并标注后续规则取代它们。

## 验证

验证静态字段直接替换、重置为系统时钟、跨线程共享、可推进时钟与分钟边界。
跑后端 clean test 并对照既有 10 项失败；跑编码规范与类型引用检查。
不暂存、不提交、不推送、不调整原暂存状态；无真实中间件验证。

## 已验证证据

- TimeProvider 中没有 ThreadLocal、Scope、override、setClock；TimeFixture 与
  TimeScopedSpecification 已删除。9 个业务规格直接给私有静态时钟字段赋值，
  并在各自 cleanup 中赋回系统 UTC 时钟。
- 移除 setClock 后的首次后端离线 clean test：1337 项、12 failures、0 errors、
  0 skipped；TimeProviderSpec 的 5 项全部通过。新增两项为卡片模型写入
  `undefined`、转换器读取 `isUndefined` 的不一致，与时钟无关。
- 用户随后选择统一为 `isUndefined`，补充一项契约规格并修复模型与 DTO 后，
  后端 clean test 为 1338 项、10 failures、0 errors、0 skipped，失败身份与
  既有基线一致。详见 [卡片字段统一设计](2026-10-08-card-series-is-undefined-design.md)。
- 编码规范检查 448 个 Java 文件、两个 POM 通过；类型扫描 560 个 Java / Groovy
  文件通过；检查器与类型引用规格合计 15/15 通过。
- 暂存区二进制 diff 的 SHA-256 与修改前相同，未进行 Git 写操作。

这些是离线证据，不代表真实中间件或现场启动已验证。
