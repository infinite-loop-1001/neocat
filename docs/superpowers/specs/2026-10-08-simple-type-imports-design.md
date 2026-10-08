# 类型引用与显式 import 全局规范化

用户已确认范围：backend、Java SDK、手写 Java / Groovy 测试和 Java 检查脚本。

## 设计

先更新编码约束，再将类型全限定引用改为显式 import + 简单类名，涵盖注解、泛型、字段、
参数、构造调用、静态访问与方法引用。仅同一源文件确有同名类型冲突时保留必要的全限定名；
嵌套类型可写 Outer.Inner。分析显式导入、同文件声明和同包类型，避免缩短后改变绑定。

不使用无边界正则全文替换：扫描时屏蔽注释、字符串和声明中的包路径；实际字符串、
配置与反射类名不修改。生成源码不手工修改，不引入依赖或改变业务行为与契约。

新增离线检查及回归，覆盖全限定注解、泛型、静态访问、嵌套类型、同名冲突、
同包和同文件声明、Groovy alias、注释与字符串。扫描器不具备完整编译器类型解析能力，
对可确定的冲突保守保留，剩余例外须审计；两端编译与动态 Groovy 测试为必要验证。

## 验证与 Git 边界

对照变更前后端失败清单；运行两端离线编译测试、规范及检查器测试、跨端契约检查。
使用 IDEA MCP 检查关键文件。真实中间件不在本轮验证范围内。

不执行暂存、提交、推送或调整已有暂存状态；只留下工作区修改供用户 review。

## 实施与验收

- 先加入 README / Java 强制约束及 AGENTS 的类型引用与 Git 操作约束，再规范化源码。
- 扫描范围：562 个手写 Java / Groovy 文件；首轮 317 个候选文件，1213 处待缩短引用。
  编译发现平台嵌套 DTO 与领域 InitRequest 同名后，保留该文件的两个领域全限定引用。
- 最终全局扫描没有无冲突的全限定类型引用；仅以下 7 处必要例外保留：
  - IngestRequestMapper：Protobuf MessageTree 两处、MetricValue 一处，与领域类型冲突。
  - PlatformConvert：领域 InitRequest 两处，与 DTO 通配导入的 InitRequest 冲突。
  - SDK NeoCat：客户端 RemoteCall 两处，与 Protobuf RemoteCall 冲突。
- 引用修复通过 IDEA MCP apply_patch 进行；package-info 注解使用简单名并导入。
  317 个候选文件的屏蔽区内容对比通过：字符串与注释未改。生成源码未手工修改。
- 新增 type-imports 扫描器和测试，接入 check-coding-standards；可单独执行
  `node scripts/check-type-imports.mjs`。检测边界已在约束文档说明，不将词法扫描等同编译器。

### 最终证据

- 后端 `mvn -o -f backend/pom.xml clean test` 实际完成测试：1337 项、10 failures、
  0 errors、0 skipped；失败身份与本轮变更前逐项一致（ModuleBoundarySpec 6 项，
  PrdAcceptanceTraceabilitySpec 4 项）。全量命令非全绿，不修复无关基线问题。
- SDK `mvn -o -f client-java/pom.xml clean test`：30/30 通过。
- 后端 `-DskipTests package` 通过；不等于可执行部署包或真实启动已验收。
- 规范扫描：449 个 Java 文件与两个 POM 通过；类型扫描 562 个 Java / Groovy 文件通过。
- 检查器与类型引用测试合计 15/15 通过，覆盖同名 DTO、java.lang 子包显式导入、
  字符串/注释保护、Groovy alias、嵌套类型与 CRLF。
- IDEA MCP 对 14 个关键文件检查无错误；HTTP 64/39 端点对齐、44 个错误码对齐通过。
- git diff --check 通过；暂存区二进制 diff 的 SHA-256 与开始时一致，未进行 Git 写操作。

以上只为离线编译、测试及源码检查证据，没有连接或验证真实中间件。
