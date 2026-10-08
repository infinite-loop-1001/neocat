# 编码规范重构离线验收记录

日期：2026-10-03。范围：backend、client-java 与规范/操作文档，保持当前 JSON / Protobuf 契约。
仓库不是 Git 仓库，未提交或推送；未连接真实中间件、未执行迁移和部署。

> 本文保留 2026-10-03 的历史验收记录。2026-10-08 已将后端注入式 Clock 改为公共静态
> TimeProvider，固定时间测试直接修改其私有静态时钟字段并在结束后赋回系统 UTC 时钟，不使用隔离或恢复作用域；领域配置迁入各自模块。
> 当前规则与验证见 `.agents/coding-standards/java.md` 和
> `docs/superpowers/specs/2026-10-08-static-time-domain-config-design.md`，下文 Clock Bean
> 与兼容构造器说明不再作为当前编码指导。

## 1. 落地结果

- 中文编码规范位于 `.agents/coding-standards/`，根 `AGENTS.md` 必须引用。
- 11 个 Controller 使用 `ResponseEntity`、各模块专用 DTO 与 Convert；
  列表/标量不增加外层包装，上报仍是 `ResponseEntity<byte[]>`。
  报表查询编排位于 `ReportQueryService` / `MetricCountQueryService`，服务不携带 HTTP 注解。
- 重复 key 策略显式声明；无手写 record、Hutool、Fastjson、UtilityClass 或自有 Optional 返回声明。
  框架返回 Optional 与局部 `ofNullable(...).orElseThrow(...)` 保留既有异常语义，
  不把 Optional 暴露为自有接口契约。
- 成员/静态变量间距由 JDK 语法树校验，覆盖注释、注解、默认访问级别、嵌套类、多行初始化；
  不用正则猜测 lambda 里的逗号。检查器自身包含有效/无效代码与语法错误回归。
- 自有组件优先扫描注册，补齐内存仓储与重载注入构造器；运行时状态在构造器初始化。
  消费循环保留 `ApplicationReadyEvent` 启动（就绪前不运行），时间使用注入 Clock。
- 元数据范围锁使用 `nc_distributed_lock` 的完整主键，先幂等建行再 SELECT FOR UPDATE。
  Spring 原生 Advisor 和业务事务共用 `mysqlTransactionManager`，不新增 AspectJ 依赖。

## 2. 可重复的离线证据

在仓库根运行：

```bash
mvn -o -f backend/pom.xml clean test
mvn -o -f client-java/pom.xml clean test
mvn -o -f backend/pom.xml -DskipTests package
mvn -o -f client-java/pom.xml -DskipTests package
node scripts/check-contract-alignment.mjs
node scripts/check-error-codes.mjs
node scripts/check-coding-standards.mjs
node --test scripts/check-coding-standards.test.mjs
node scripts/check-protocol-drift.mjs
cd front && npm test && npm run flow && npm run build
```

| 验证 | 结果 | 边界 |
|---|---|---|
| 后端全量规格 | 1328 项，0 失败/错误/跳过 | 不连接中间件；含 ModuleBoundarySpec |
| Java SDK | 30 项，0 失败/错误/跳过 | 无内置 HTTP sender 连通性验收 |
| 两端 package | 通过 | 普通 jar，不代表可执行部署包或已启动 |
| 前端 test / flow / build | 103 项 / 47 个检查点 / 构建通过 | mock，不代表真实后端联调 |
| URL 对齐 | 64 个后端端点，39 个前端 mock 端点全部有对应 | 不证明真实权限或写入 |
| 错误码对齐 | 44 个编号一致 | 未新增错误码或改变错误体 |
| 编码规范扫描 | 434 个手写 Java、两个 POM，通过 | 不扫描生成代码；建议例外见下 |
| 检查器自身规格 | 通过 | 语法树测试，无中间件 |
| Protobuf 同源 | 单一 proto，19 个生成类逐字节一致 | 不替代真实上报处理链路 |
| 两端依赖树禁用项过滤 | 未发现 Hutool / Fastjson / Fastjson2 | 使用离线 Maven 当前依赖解析 |

新增/加强的证据：

- `HttpDtoContractSpec`：20 项，真实 Jackson 请求体绑定、身份省略规则/敏感字段隔离、
  组织/平台/目录/告警/Trace/队列响应、卡片 null 缺口与除零，以及 HTTP 方法返回类型。
- `ReportHttpSerializationSpec`：通过真实 MockMvc 比对报表/Metric 查询读模型与 DTO JSON，
  覆盖默认参数、Long count、空列表、缺数、环比点、字段名和数值类型；19 个场景全部通过。
  对比基准为重构中保留的旧读模型，不是原部署系统抓包基线。
- `AnalysisComponentWiringSpec`：六个分析器集合、仓储/分钟读取注册、应用就绪前不启动消费。
- `ComponentScanWiringSpec`：真实业务组件扫描与代理组装、11 个 Controller，
  数据源和 Mapper 仅 Spock Mock / Stub，断言没有取数据库连接；不启动调度与 Apollo 网络。
- `MySqlDistributedLockSpec`：15 项，包括 SQL 顺序、有限超时、锁失败不运行临界区、
  未命中锁行/建行失败回滚、业务异常传播、真实 Spring 注解代理，
  嵌套锁 + `@Transactional` 只取一次 Mock JDBC 连接，最外层提交或回滚/关闭一次。

Maven 输出可在 `backend/target/surefire-reports/` 与 `client-java/target/surefire-reports/` 复核。
测试中故障注入会输出 ERROR/WARN；以规格结果和 Surefire 汇总判定，不以日志无 ERROR 判定。

## 3. 建议项保留方式与理由

- 数据源、事务管理器、Clock、具名数据源及接口适配工厂仍允许 `@Bean`。
  `QueryWiring` 保留历史/内存数据口路由、SamplePort、元数据适配工厂；
  这不是重复注册简单服务，组件扫描规格已经验证没有缺 Bean 或冲突。
- 测试兼容构造器保留 `Clock.systemUTC()` 委托，实际 Spring 构造器显式注入 Clock。
  请求 DTO 的单字段属性构造器显式 `JsonCreator(PROPERTIES)`，两端 `-parameters` 保留。
- ReportConvert 在边界用注入 Jackson 把固定读模型转专用 DTO，
  暂不为风格替换内部 Map 读模型；MockMvc 字段级比对负责守住 HTTP 出口。
- 必需查询消费端局部 Optional 保留原异常码，不继续扩大为无关业务行为重写。

## 4. 现场状态与既有缺口

真实 MySQL / ClickHouse / Apollo 全应用装配、迁移、MyBatis 同连接往返、双连接互斥、
驱动取消/死锁行为均 **NOT_RUN**。已有库升级先审批执行
`migrations/2026-10-03-distributed-lock.sql`，再按 `mysql-lock-verification.md` 和 T33/T34 验证。
锁事务 15 秒/JDBC 查询 10 秒不是 HTTP 硬截止时间；实际阻塞等待需现场验证。

元数据锁不是执行去重或跨系统原子事务。各实例内存桶仍各自刷盘；
告警内存窗口跨实例连续性与通知去重未解决，外部 notifier 仍抛不支持异常。

本次保留而不顺便重定义的已知行为：

- `AlertRuleService.save()` 生成关闭状态的返回对象，未调用仓储持久化；真实创建规则不可据此判通过。
- 卡片 HTTP 保存入口仍忽略请求阈值线；响应阈值字段保持 direction / value，不能声称保存已实现。
- 前端真实模式仍需同源反向代理或补 Vite `/api` 代理。
- SDK 无内置 HTTP sender；上报 `X-NC-Token` 校验及会话拦截覆盖问题仍待独立联调。
- ProblemAnalyzer 的慢阈值仍在构造时读取，不以组件化宣称运行中阈值自动生效。

因此本记录结论为：**规范化改造及离线回归通过，不是生产就绪或真实端口验收通过**。
