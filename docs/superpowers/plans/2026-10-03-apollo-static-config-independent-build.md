# 实施计划

1. 将根 POM 的必要属性、BOM 与编译配置迁入 backend/client-java，各自验证后删除根 POM。
2. 按用途添加 @Configuration(proxyBeanMethods=false) 配置类，使用 @ApolloStaticValue 和 public static volatile 字段。
3. 接通原生 Apollo 处理器与引导参数（`-Dapp.id`/`APP_ID`/`META-INF/app.properties` 与 `-Dapollo.meta`/`APOLLO_META`），以 `ApolloConfigGuard` 做启动门禁，通过初始化依赖保证配置先就绪。
4. 删除 RuntimeConfig 接口及启动快照实现，将业务读取、幂等窗口、迟到策略、Top N、Trace/Alert 与消费循环迁移为静态字段直接读取。
5. 调整动态队列容量、消费线程和分钟延迟检查；不改历史固化数据，不新增未实现配置键的业务功能。
6. 迁移离线测试装配，使用隔离静态值的测试设施；增加真实处理器初始绑定/变更及同一业务对象热更新回归。
7. 更新有效脚本和部署文档，运行两端 clean verify、打包/协议检查，自查配置缓存及模块边界。

不使用子代理；本目录无 Git 仓库，不执行提交或推送。真实数据库/Apollo 推送验收单独标记未执行。

## 完成与自查

1–7 已执行。两端分别 clean verify：backend 1265 项、SDK 30 项通过，无失败/错误/跳过；真实 Apollo 自动配置/监听、同对象热更新、资源调整与调度回归通过。HTTP/错误码/协议契约检查、有效 POM、-parameters 及显式 Boot repackage 核对通过。

未现场验收和 11 个已声明但未消费配置键明确记录在 `docs/neocat-technical-design/12-apollo-static-config-status.md`；不补未实现鉴权、通知、采样等功能。已人工自查初始化优先级、数据不丢、定稿不重写及依赖版本管理。本会话无可用 code-review 技能，未调用未提供的技能；无 Git 仓库，不能提交。
