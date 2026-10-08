# 公共静态时间与领域配置归属

> 后续评审已取消本文件原有线程隔离、嵌套和恢复作用域方案，并进一步取消任何设置时钟的方法。
> 当前时间测试方式以 [global-static-clock-design](2026-10-08-global-static-clock-design.md) 为准：
> 单测直接给 TimeProvider 的私有静态时钟字段赋值并在结束后赋回系统 UTC 时钟。
> 下文旧方案与验收记录保留为历史证据。

## 已确认目标

落实两条评审注释：

- `RuntimeConfiguration.java:30`：公共时间能力使用静态入口，不通过 Spring Bean 注入。
- `AlertConfig.java:6`：领域配置归所属业务模块，不集中存放于 `common`。

实施顺序是先更新编码规范，再全局修改代码与引用。保持 HTTP JSON、错误码、Protobuf、
Apollo 配置键、配置字段类型与运行参数语义不变。不顺带修复其他架构或产品基线问题。

## 时间能力

在 `com.neocat.common.time.clock` 提供普通工具类 `TimeProvider`，使用显式私有构造器。
生产默认使用不可变 UTC 系统 Clock；提供静态 `now()` 和 `millis()`，替代生产中的
Clock / ClockProvider 注入与对应取时调用。业务时区仍来自现有平台配置，不改为 UTC 业务时区。
仅取当前时间的地方改变依赖方式；显式传递的事件时间、时间桶边界和时间计算参数保持原状。
SDK 使用的单调计时、超时与耗时测量不改为墙上时钟。

移除 RuntimeConfiguration 的 Clock Bean，清除生产中的时钟字段、构造参数、装配参数与
SystemClockProvider Spring 组件。同步调整手工构造调用、Groovy 规格和测试装配，避免
动态类型测试在运行时才暴露构造器不匹配。确认无剩余使用后移除 ClockProvider 接口。

测试通过同包测试夹具调用非公开的覆盖入口，使用线程隔离的 Clock 覆盖作用域；支持嵌套，
关闭时恢复上一层，最外层关闭时清理线程状态。测试使用固定或可推进 Clock，不使用全局可变
系统 Clock，也不依赖机器真实时间。跨线程执行不会隐式继承覆盖；确需测试异步时间逻辑时在
执行线程显式建立作用域。生产代码不得使用测试覆盖入口。

保留取时次数、业务短路顺序、时区、毫秒精度和异常语义；不将一次读取的时间拆成多次读取。

## 配置归属

八个动态配置类迁移至业务模块的 `config` 子包：

| 配置 | 所属模块 |
| --- | --- |
| AlertConfig | alert |
| IngestConfig | ingest |
| AnalysisConfig | analysis |
| ReportConfig | analysis（报表写入、调度与保留参数） |
| MetricConfig | analysis（指标 Top-N 聚合参数） |
| TraceConfig | trace |
| QueryConfig | query |
| HeartbeatConfig | query（心跳查询展示参数） |

迁移不改变类名、Spring 默认 Bean 名、Apollo 注解内容、`public static volatile` 字段，
保留已有 `@DependsOn` 的初始化依赖。同步修改生产源码、测试夹具和当前操作文档中的路径。
技术文档中的历史状态记录明确标注迁移，不伪造过去验证结果。

跨模块确有配置使用时，仅通过明确的 `config` 具名接口访问，并声明最小必要的
allowedDependencies；禁止开放整个基础设施包，禁止把业务配置复制回 common 或建立配置副本。
新增暴露类型和删除的公共导出类型须同步更新 named-interface-types.properties。

## 启动装配

将 RuntimeConfiguration 与 ApolloConfigGuard 移至应用根包 `com.neocat` 的启动装配层，
与 NeoCatApplication 同层；不新增会被 Modulith 识别为业务模块的 bootstrap 子包。
这样避免 common 反向引用迁移后的业务配置而形成模块循环依赖。
保留 Spring 扫描、静态 BeanFactoryPostProcessor、Apollo 属性源安装之后且业务 Bean 创建
之前校验的时序，以及队列工厂装配。

ApolloConfigGuard.DYNAMIC_CONFIG_TYPES 继续完整注册八个配置类，缺键或非法值仍阻止启动；
不得以删除校验或放宽必需键绕过迁移问题。

## 规范与自动回归

先在 `.agents/coding-standards/README.md` 与 `java.md` 写入两条强制规则，修订
`http-api.md`、AGENTS.md 与当前技术文档中的“注入 Clock”要求，消除互相矛盾的指导。
规范检查增加生产时钟注入、绕过公共入口直接获取墙上时间、公共包持有领域动态配置的检测。
检测范围和允许的公共实现/测试夹具例外须明确，不将 JDK 时间类型本身一概禁止。

## 验收

1. IDEA MCP 全局检索：生产无 Clock / ClockProvider 注入，公共包不再持有八个领域配置，
   旧全限定引用清零，关键文件无 IDE 错误。
2. 时间工具规格覆盖默认 UTC、固定时间、millis 一致性、嵌套恢复、异常后恢复和线程隔离。
3. 固定时间业务测试保持原断言；离线 Spring 装配不再提供或依赖 Clock Bean。
4. Apollo 启动校验规格覆盖八类注册、缺键、非法值与初始化时序；无网络或中间件。
5. 规范检查器及自身规格通过；两个 Maven 模块离线编译/测试；契约、错误码及差异格式检查。
6. 与现有后端失败清单比较，单独报告新增失败；不得以既有失败为理由忽略迁移新增越界或循环。

离线证据不代表真实 Apollo、MySQL、ClickHouse、外部投递或生产运行已验收。

## 实施与验收（2026-10-08）

- 先更新 README / Java / HTTP API 强制规范与 AGENTS 的时间指导，再实施源码迁移。
- TimeProvider 提供静态 now / millis；系统 Clock 只存在于公共实现内部。测试覆盖入口非公开，
  由同包 TimeFixture 使用；TimeScopedSpecification 在 feature 清理期逆序恢复覆盖。
  作用域验证线程身份与嵌套顺序，即使嵌套使用同一个 Clock 也不能乱序关闭。
- 移除 Clock Bean、ClockProvider / SystemClockProvider、生产时钟字段与构造参数，
  同步调整 Groovy 手工构造、固定时间测试和离线装配。JdbcReportBucketSink 的版本墙上时间
  也经静态入口获取；保留 AtomicLong 单调递增策略。SDK 源码与其计时方式未改。
- 八个配置类完成归属迁移；逐一比对迁移前后源码，配置键、注解、字段类型与修饰符未改变。
  保留 @DependsOn Bean 名及八类完整启动校验注册。
- ApolloConfigGuard / RuntimeConfiguration 移至 com.neocat 应用根包，不新增业务模块。
  ingest 与 trace 的 config 具名接口仅分别导出 IngestConfig 和 TraceConfig；analysis / query
  增加对应最小依赖。更新导出基线，保留既有 organization 命名问题不作无关修复。
- 检查器新增生产时钟字段/参数/返回声明、直接系统墙上取时（含静态导入、方法引用）和
  common 中 Apollo 动态字段检测；仅公共实现与测试源码允许 Clock。仍是语法检查，不代替
  类型解析与实际装配测试。修复当前提交中已损坏的检查器夹具全限定 import 名，保留测试意图。

### 已验证证据

- IDEA MCP：八类旧配置全限定引用清零，生产 ClockProvider 引用清零，关键文件错误检查为空。
- 编码规范检查：449 个手写 Java 文件、两个 POM 通过；检查器规格 6/6 通过。
- 后端最终离线 test：1337 项，10 failures、0 errors、0 skipped；失败身份逐项与变更前一致：
  ModuleBoundarySpec 6 项、PrdAcceptanceTraceabilitySpec 4 项，没有新增失败。
- 10 项基线失败中两项消息展示随导出集合和无序模块顺序变化；其他失败消息相同。
  Modulith 完整 verify 仍被既有 organization 具名接口名称不匹配阻断，不能称其全绿。
- 独立 TimeConfigArchitectureSpec 3/3：真实生产模块依赖图无循环、common 无业务反向依赖、
  跨模块配置访问归属和 config 导出范围符合设计，不依赖被阻断的 Modulith verify。
- TimeProviderSpec 5/5：默认时间边界、固定时间/毫秒、嵌套恢复、异常恢复、线程隔离、
  同 Clock 嵌套乱序拒绝、重复关闭、可推进 Clock、异线程关闭拒绝。
- ComponentScanWiringSpec：离线业务装配通过，确认无 Clock Bean、不取真实数据库连接；
  ApolloStaticWiringSpec / RuntimeConfigSpec：绑定、热更新、启动校验及注册归属通过。
- SDK clean test：30/30；HTTP 64 个后端端点与 39 个前端 mock 端点对齐，44 个错误码对齐。
- git diff --check 通过；未修改前端、SDK、proto、POM 或生成源码，未连接真实中间件。

以上是离线测试与代码结构证据，不代表真实 Apollo 推送、全应用现场启动或外部投递已验收。

## 补遗：分钟点计算收归公共时间能力（2026-10-08）

评审指出 `AlertController` 的预告警时间表达式（`now().minusSeconds(delay).toEpochMilli() / 60_000 * 60_000`）
属于时间计算，应由公共时间能力提供。新增 `TimeProvider.delayedMinuteStart(delaySeconds)`：
返回「当前时刻回退 delaySeconds 后向下对齐到分钟边界」的 `Instant`。延迟仍由调用方
从句柄模块的领域配置读取后传入，TimeProvider 不感知业务配置，也不判断该分钟点是否已完全落库。

- `AlertController.preview` 与 `AlertEvaluationJob.evaluate` 改用该能力；判定分钟点在其上
  `minusSeconds(60)`，保持「上一个已完成分钟点」的语义、时区与毫秒精度不变。
- 检查器新增生产代码「epoch 毫秒取整」检测：`toEpochMilli()` / `millis()` 与一分钟毫秒字面量
  相乘或整除即报错，避免调用方再次手写分钟对齐；规则与示例写入 README / java.md。
- 时间为固定时钟的 `TimeProviderSpec` 新增能力规格（延迟 5s、90s 跨小时边界与 0 延迟），
  规格由 5 项增至 6 项；`AlertEvaluationJobSpec`、`DynamicHttpConfigSpec`、`PreviewSpec`
  仍按原断言通过，确认外部行为未变。

### 补遗证据

- 定向规格：TimeProviderSpec 6/6、AlertEvaluationJobSpec 8/8、PreviewSpec 20/20、
  DynamicHttpConfigSpec 5/5，合计 39 项通过。
- 后端全量离线 `clean test`：1338 项（含新增 1 项能力规格），10 failures、0 errors；
  失败身份与既有基线逐项一致，无新增、无消失。
- 编码规范检查（含分钟点取整规则）449 个 Java 文件通过；检查器与类型引用规格 15/15 通过；
  类型引用扫描 562 个 Java / Groovy 文件通过；`git diff --check` 通过。
- IDEA MCP 对 TimeProvider、AlertController、AlertEvaluationJob、TimeProviderSpec
  检查无错误。未执行任何 Git 写操作，暂存区 diff 哈希仍为变更前值。
