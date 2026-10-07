# Java 判空规范统一

## 目标与范围

落实 `AlertConvert.rule` 的评审规则：手写 Java 的 null 判断必须使用
`Objects.isNull(value)` / `Objects.nonNull(value)`，不得使用 `== null` / `!= null`。
覆盖 backend、client-java 的生产代码与 Java 测试，以及 scripts 中的手写 Java。
不修改 target 生成代码、前端、Groovy；集合可变性与 `subList()` 规则另行记录在本文第三轮。

## 规范与实现

- README 将判空列为强制项，java.md 给出正反示例与适用边界。
- 保留 `Objects.requireNonNull` 的必需参数校验语义；允许使用标准方法引用。
- 基于 JDK 语法树识别相等/不等表达式，包括反向比较与带括号的 null。
- 替换时只改比较节点，不移动被检查表达式，不改变执行次数、短路顺序、异常或默认值。
- 使用 `java.util.Objects` 导入，存在名称冲突时使用全限定名。
- 注释与字符串不参与替换，已处理的 AlertConvert 判空评审注释移除。

## 自动检查

扩展现有 CheckJavaStandards 的语法树扫描，拒绝任一侧是 null 的相等/不等比较。
脚本同时检查 backend/src、client-java/src 和 scripts 下的手写 Java，排除生成源码。
检查器规格覆盖左右比较、括号、复合条件、三元、lambda，以及注释/字符串误报隔离。
不新增依赖，不连接中间件。

## 验收

1. 语法树扫描无违规，git diff 不涉及范围外逻辑或 API 契约。
2. 编码规范检查与检查器自身规格通过。
3. 两个 Maven 模块离线测试；后端变更前后失败清单对比，区分既有失败和新增回归。
4. 语义测试验证 null/非 null、短路和带副作用表达式只求值一次。

用户已确认上述实施范围；仅按该设计实施，不作额外重构。

## 实施验证（2026-10-07）

- 语法树确认并修正 386 处比较、105 个 Java 文件：backend 361 处 / 101 文件，
  client-java 20 处 / 3 文件，scripts 5 处 / 1 文件。
- 规范检查通过，覆盖 447 个手写 Java 文件和两个 POM；未修改生成实现。
- 检查器自身 3 项测试全部通过，覆盖违规定位、注释/字符串隔离及短路/副作用语义。
- 后端 `mvn -o -f backend/pom.xml clean test`：1328 个测试，10 failures、0 errors。
  变更前后失败测试相同：ModuleBoundarySpec 6 个、PrdAcceptanceTraceabilitySpec 4 个。
  两条失败信息仅集合展示顺序不同，归一化无序集合后 10 条失败信息与基线一致。
- SDK `mvn -o -f client-java/pom.xml clean test`：30 个测试全部通过。
- HTTP 契约对齐与 `git diff --check` 通过。
- 104 个后端/SDK 改动文件与原始源码逐一核对，除 null 比较替换、Objects 导入、
  移除已处理的判空评审注释外，无其他逻辑修改。

以上为离线证据，不代表真实中间件、Spring 运行装配或外部通知已验收。

## 第三轮：空容器工厂与独立集合（2026-10-07）

### 目标与范围

落实 `AlertConvert.java:35` 与 `AlertEngine.java:99` 的评审规则：纳入范围的手写 Java
不得使用空的 `List.of()`、`Set.of()`、`Map.of()`，统一改用 Guava 可变工厂；源码任何位置
不得使用 `subList()`，截取必须产生独立集合。覆盖 backend、client-java、手写 Java 测试与
`scripts`，不修改生成代码、Groovy 或前端。

### 实现要点

- backend 与 client-java 显式声明 Guava `33.0.0-jre`。
- 空 List/Set/Map 分别改为 `Lists.newArrayList()`、`Sets.newHashSet()`、`Maps.newHashMap()`；
  非空 `List.of(...)` 等调用保留原有只读语义。
- 三处生产代码的 `subList()` 改为索引复制，保留顺序、边界与独立集合语义。
- `CheckJavaStandards` 新增空 JDK 容器工厂和 `subList()` 检查，并增加对应规格测试。

### 验证

- IDEA MCP 复查：5 个关键修改文件无错误；backend、client-java、scripts 的手写 Java 中空
  `List.of()`、`Set.of()`、`Map.of()` 与 `.subList(` 均为 0 处。
- `node --test scripts/check-coding-standards.test.mjs`：5 项全部通过；
  `node scripts/check-coding-standards.mjs`：447 个手写 Java 文件与两个 POM 通过。
- `mvn -o -f backend/pom.xml clean compile`：BUILD SUCCESS，452 个源码文件编译通过。
- `mvn -o -f client-java/pom.xml clean test`：30/30 通过。
- `mvn -o -f backend/pom.xml clean test`：1328 个测试，10 failures、0 errors；失败仍为既有的
  `ModuleBoundarySpec` 6 个与 `PrdAcceptanceTraceabilitySpec` 4 个，未增加新的失败。
- `git diff --check` 通过。以上为离线证据，不代表真实中间件或外部投递已验收。

## 第二轮：容器判空与相等（2026-10-07）

### 目标与范围

落实 `RecipientService:102`、`AlertEngine:139` 与 `AlertConvert:33` 的评审规则：

- 容器/Map 判空统一使用 Apache `CollectionUtils` / `MapUtils`（含 `emptyIfNull`），
  不再手写 `Objects.isNull(x) || x.isEmpty()`、`size() == 0`、裸 `x.isEmpty()`。
- 对象相等使用 `Objects.equals(left, right)`；枚举与原始类型仍用 `==`。
- stream 前对可空容器使用 `emptyIfNull(...).stream()`。

依赖变更：`backend/pom.xml` 新增 `org.apache.commons:commons-collections4:4.5.0`。
Spring Boot 3.3.13 BOM 不管理该构件，因此显式声明版本。`client-java` 不新增任何依赖，
容器判空沿用 JDK（作为规范中的显式例外）；`scripts` 只用 JDK。

### 实现要点

- 基于 JDK 语法树 + Lombok 处理器路径做类型感知改写，共 **190 处 / 75 文件**：
  `equals` 调用 77、对象引用 `==/!=` 3、容器判空 124、`emptyIfNull` 11、
  冗余条件删除 22（`Objects.nonNull(x) && ... && !x.isEmpty()` 合并为 `isNotEmpty(x)`）。
- 排除枚举 `==`（74 处）：枚举常量引用唯一，`==` 不会 NPE 且是惯用写法。
- 排除 `String.isEmpty()`：它不属于容器判空规则。
- 保留文件原有 import 分组与顺序，仅追加缺的导入。
- 删除已实现的评审注释：`RecipientService` 的 stream 判空注释、`AlertConvert` 的
  `List.of()` 注释（其“CollectionUtils 能创建可变空容器”的前提不成立：
  `CollectionUtils.emptyCollection()` 同样返回不可修改集合，实测 add 抛
  `UnsupportedOperationException`；且它返回 `Collection` 而非 `List`，无法直接替换）。

### 语义边界（不改行为）

- `Objects.isNull(容器)` 改为 `isEmpty(容器)` 会收窄为「null 或空」；改动前逐处核对，
  12 处均满足外层另有 `isEmpty` 判断，或空容器与 null 原本等价处理。
- `a.equals(b)` 改为 `Objects.equals(a, b)` 会把原本的 NPE 变为返回 false；
  本次未扩大这一语义变化，仅统一既有位置。
- 判空工具仅用于 backend，未改动生成代码、前端或 Groovy。

### 验证（2026-07-07 第二轮）

- `node scripts/check-coding-standards.mjs`：447 个手写 Java 文件通过；新增容器判空规则
  覆盖 `Objects.isNull(x) || x.isEmpty()`、`Objects.nonNull(x) && !x.isEmpty()`、
  `size()` 与 0 比较，并排除 `String`。
- `node --test scripts/check-coding-standards.test.mjs`：4 项全部通过（新增 1 项容器判空规格）。
- 后端 `mvn -o -f backend/pom.xml clean test`：1328 个测试，10 failures、0 errors；
  与基线相同的 10 个失败（ModuleBoundarySpec 6、PrdAcceptanceTraceabilitySpec 4），
  归一化集合展示顺序后失败信息一致，无新增回归。
- SDK `mvn -o -f client-java/pom.xml clean test`：30 个测试全部通过。
- 逐行核对 79 个改动文件：新增行仅为 import 或工具类调用，无其他逻辑改动。
  HTTP 契约对齐与 `git diff --check` 通过。
- 已知局限：容器判空检查只识别语法上可确定的形态；单独出现的 `list.isEmpty()`
  在没有 classpath 的语法树检查中无法与 `String.isEmpty()` 区分，未纳入自动检查。
