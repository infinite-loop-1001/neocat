# Apollo 静态动态配置与独立 Maven 构建

日期：2026-10-03
状态：用户已确认实施；代码、测试与独立构建已迁移，验收见配置迁移状态文档。

## 用户要求

- 使用已引入的 `link.cu1universe.dev:common-apollo:1.0.0-SNAPSHOT`。
- Apollo 动态配置全部迁移为 `@ApolloStaticValue` + 静态字段。
- 配置类按作用拆分，不设集中配置类；使用 `@Configuration` 注册。
- 使用配置的代码直接读取静态字段，不能保留配置副本、构造器快照、循环外快照或配置值的局部别名。
- backend 与 client-java 各自独立构建，不再继承项目根 POM，随后删除根 `pom.xml`。

## 已核实的依赖行为

来源：`infinite-loop-1001/cu1-common-abilities` 的 common-apollo 源码及本地依赖 JAR。

- 注解包：`link.cu1universe.dev.apollo.annotation.ApolloStaticValue`。
- 处理器是 `BeanPostProcessor`，在 Bean 初始化前扫描其字段；不扫描未注册的普通类。
- 字段必须为非 final 的 static 字段，注解值必须包含单个 Spring 属性占位符。
- 初始值从 Spring Environment 解析，后续监听 `apollo.bootstrap.namespaces` 中的命名空间。
- 自动配置依赖 Apollo 原生 `ApolloAnnotationProcessor` Bean。当前启动入口禁用 bootstrap、仅装载在线快照，因此必须核验并修正监听链路，不能只增加字段注解。
- 类型转换失败时依赖会记录错误并保留旧值，不能将这一行为描述为应用已完成范围校验。
- 处理器 INFO 日志包含配置原值；须关闭该类的值日志，避免令牌等敏感值泄露，仍保留错误日志。

## 设计

### 1. 按作用分配置类

按上报接收、分析、报表、Metric、Trace、告警、查询、Heartbeat 等作用拆分；最终类名与包位置遵循现有模块边界，公共配置置于 common 的具名配置接口中，避免新增模块依赖环。

各类使用 `@Configuration(proxyBeanMethods = false)`，字段声明为 `public static volatile` 并标注 `@ApolloStaticValue("${原配置键}")`。不使用集中配置 Bean、配置 getter 或新的配置快照 Map。字段保留现有类型与键名，不把 static 字段声明为 final。

业务读取必须在实际使用位置引用静态字段。原 `RuntimeConfig` 接口、`DefaultRuntimeConfig` 与配置快照装配在调用方迁移后移除或拆解；时钟和队列工厂等非配置职责保留。

### 2. 初始化与运行期

- 保留现有在线启动预检及必需键校验：Apollo 不可达或缺键不允许启动，不以本地缓存作为启动兜底。
- 在线预检已删除：服务发现、远端读取与缓存回退由 Apollo 客户端负责；启动后由 `ApolloConfigGuard` 校验必需键，缺键或非法即拒绝启动。
- 将引导参数一致提供给实际 Apollo 客户端；确保原生处理器与 common-apollo 处理器注册成功并监听正确命名空间。
- 消费配置的 Bean 显式依赖相关配置类的初始化，不依靠扫描顺序、类加载顺序或 `@Order` 猜测初始化时机。
- 生产动态字段不使用默认占位符掩盖必需键缺失。离线测试独立设值并恢复静态字段，不访问真实 Apollo。

### 3. 消除配置缓存并保证行为更新

审计全部动态配置的读取点和派生对象，包括构造器字段、Duration、批处理循环、调度表达式、缓存 TTL、Top N、查询限制、采样与留存参数。

- 标量校验、批大小、超时、取样条数、留存边界等直接使用静态字段，每次调用读取最新值。
- 队列容量与消费者线程数不仅更新字段，还需让相应运行资源响应新配置；保留非阻塞接收、过载丢弃计数、在途任务与停机语义。资源对象与实际工作状态可以保留，但不能作为业务配置副本。
- Top N 更新作用于后续适用决策，不重写已定稿小时的归属或既有报表数据。
- 定时器、缓存与数据库 TTL 等不能仅靠字段更新生效的部分，必须核对现有实现并在各自资源使用边界落实更新；不把历史数据重算或改表结构作为本次默认行为。
- 已声明但没有业务消费者的配置列入审计结果，不凭空增加鉴权、通知投递等原本未实现的功能，也不宣称这些键已影响业务。

这里的“全部迁移”指全部 Apollo **动态配置**，不是把 server.port、数据库连接、MyBatis 装配等框架启动参数伪装成可热切换资源。MySQL 中管理的平台业务数据与未接入 Apollo 的 SDK `ClientConfig` 不改为服务端静态配置。

### 4. 独立 Maven 构建

- backend/pom.xml 和 client-java/pom.xml 去掉对 `com.neocat:neocat` 的 parent 引用，各自声明现有 groupId、version、所需属性、依赖管理和插件配置。
- backend 保留 Spring Boot/Modulith/Spock 版本及已引入的 common-apollo 依赖和快照仓库；client-java 只保留其需要的依赖管理，不引入 Spring 或 Apollo。
- 保留 Java 17、UTF-8、编译参数 `-parameters`、backend 的 Lombok 处理器、Groovy/Spock、Surefire 与 Protobuf 生成配置。
- 两端继续从 `../proto` 独立生成协议，不增加两端之间的 Maven 依赖，不另外采用 common-parent。
- 删除根 pom.xml；当前有效的构建脚本和部署文档改为 `mvn -f backend/pom.xml …` 与 `mvn -f client-java/pom.xml …`，不能再使用根 reactor 的 `-pl/-am` 命令。
- 保持现有产物坐标、依赖版本和明确的 Boot repackage 行为，避免因拆 POM 顺带升级依赖。

## 验证与完成标准

1. 用真实 common-apollo 处理器和内存 Apollo 配置模拟初始绑定与变更，不连接外部中间件；验证配置类注册、初始化顺序与 volatile 字段刷新。
2. 构造业务对象后再修改静态字段，验证同一对象读到新配置；覆盖原先存在缓存的幂等窗口、迟到限制、Top N、批处理、Trace 和 Alert 使用点。
3. 补充队列/线程调整、缓存/调度适用边界回归，保护在途数据、断线、停机与已定稿数据语义。
4. 测试静态值在用例结束后恢复，避免测试污染；更新模块导出基线与相关测试装配。
5. 根 POM 删除后分别执行两个项目的 clean verify，核对有效 POM、产物坐标、协议生成一致性、请求体构造器参数名与 Boot 打包。
6. 静态审计生产调用点没有旧 RuntimeConfig getter、动态配置实例字段或局部缓存，脚本和有效文档不再依赖根 POM。
7. 真实 Apollo 推送、数据库 TTL SQL 和生产资源行为如未现场验证，明确记录为未验收，不以离线替身宣称生产成功。

## 流程与自查

- [x] 探索当前代码、POM、动态配置读取点及依赖源码。
- [x] 本任务不涉及视觉方案，不使用视觉伴侣。
- [x] 对比集中类与按作用拆分类：用户选择按作用拆分。
- [x] 澄清 Bean 注册：用户选择 @Configuration，而非 @Component 或 @Bean。
- [x] 写出修订方案；检查键名、初始化、运行期、独立构建和测试范围无相互冲突。
- [x] 用户确认本方案后制定实施计划并执行。

当前目录不是 Git 仓库，不能提交设计文档或后续改动；不创建仓库、不推送外部源码，也不修改 common-apollo 上游。
