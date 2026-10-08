# NeoCat 编码规范

修改项目之前必须阅读并遵守本目录规范。适用于 backend、client-java、测试与依赖声明；生成代码不手工修改。强制项不得以「建议」为由绕过。建议项采用推荐方式，必要例外须说明理由并提供测试。

## 强制

1. 跨实例互斥使用 MySQL InnoDB `SELECT … FOR UPDATE`，按完整主键或唯一索引键等值查询；详见 [mysql-locking.md](mysql-locking.md)。
2. Stream 的 `Collectors.toMap` / `toConcurrentMap` 必须显式处理重复 key，策略由业务决定，禁止无理由静默覆盖。
3. 禁止声明 Java `record` 类型，使用普通类与标准 getter，保留 Jackson 所需的构造器参数名。
4. 禁止引入或调用 Hutool、Fastjson（含 Fastjson2）；JSON 使用 Jackson。
5. 对外 Controller 使用专用 DTO 与 Convert，统一 `ResponseEntity<T>`；详见 [http-api.md](http-api.md)。
6. 相邻成员字段、静态变量之间必须空一行，包括嵌套类。
7. Convert / Converter 必须使用 MapStruct，禁止手写静态转换工具类；Spring 管理的转换器使用 `@Mapper(componentModel = "spring")`，调用方通过构造函数注入。具体映射与特殊逻辑边界见 [http-api.md](http-api.md)。
8. Java 判空必须使用 `Objects.isNull(value)` / `Objects.nonNull(value)`，禁止使用 `== null` / `!= null`（包括反向比较）；必需参数校验保留 `Objects.requireNonNull`。详见 [java.md](java.md)。
9. 容器与 Map 的判空统一使用 Apache `CollectionUtils` / `MapUtils`（`isEmpty`、`isNotEmpty`、`emptyIfNull`），不使用 `size() == 0`、`== null || isEmpty()` 等手写形式；`Objects.equals()` 用于判断对象相等（枚举同样适用，只有原始类型保留 `==`）。所有空 List、Set、Map 统一使用 Guava `Lists.newArrayList()`、`Sets.newHashSet()`、`Maps.newHashMap()`，禁止空的 `List.of()`、`Set.of()`、`Map.of()`；源码中禁止使用 `subList()`。详见 [java.md](java.md)。

10. 后端当前墙上时间统一通过 `TimeProvider.now()` / `millis()` 公共静态入口获取，禁止通过 Spring 注入 Clock / ClockProvider，禁止在调用方直接读取系统墙上时间；延迟回退后的分钟起点使用 `TimeProvider.delayedMinuteStart(delaySeconds)`，禁止手写毫秒取整。TimeProvider 不提供设置时钟的方法，Groovy 单测直接给其 `private static volatile Clock` 字段赋值，结束后赋回 `Clock.systemUTC()`，不使用线程隔离或恢复作用域。SDK 的单调耗时计时不适用本条。
11. 领域动态配置归所属业务模块的 `config` 包，禁止放到 `common`；跨模块访问只开放必要的具名接口。启动装配与 Apollo 必需键校验位于应用根包，不得造成 common 反向依赖业务模块。
12. 类型引用统一使用显式 `import` 与简单类名，注解、泛型、构造调用、静态成员和测试同样适用；仅同一源文件确有同名类型冲突时保留必要的全限定名。详见 [java.md](java.md)。

13. 对外 HTTP 接口必须用 SpringDoc + OpenAPI 3 注解声明文档：Controller 有 `@Tag`、每个端点有 `@Operation`、DTO 有 `@Schema`；只生成 `/v3/api-docs`，不引入 Swagger UI。详见 [http-api.md](http-api.md)。

## 建议

1. Spring Bean 使用构造函数注入；实例运行时字段初始化放进构造函数。自有组件优先 `@Service` / `@Component`，不为简单构造专设 Wiring。
2. 不使用 Lombok `@UtilityClass`，工具类用显式私有构造器。
3. 自有函数不返回 `Optional`；可空查询明确标注可空，必需查询抛明确业务异常。JDK / 框架返回的 Optional 立即消费，禁止返回 null 的 Optional。
4. 集合辅助操作优先 JDK 或 Apache 工具类；Java 判空遵守上述强制项。不使用 Hutool、Fastjson 或 Spring 的 CollectionUtils 替代。容器判空所需的 `commons-collections4` 仅 backend 声明；Guava 在 backend 与 client-java 显式声明，用于空可变容器和独立集合。

执行细节及示例见 [java.md](java.md)。离线测试不代表真实中间件验收通过。

## 自动回归

仓库根运行 `node scripts/check-coding-standards.mjs`（需要 JDK 17+）：源码禁用项、
JDK 语法树解析成员间距/重复 key/Optional 返回声明/null 比较/容器判空组合与 `size()` 比较、空 JDK 容器工厂、`subList()`、生产时间入口、分钟点毫秒取整和领域配置归属、接口文档注解（`@Tag`/`@Operation`/`@Schema`）、两个 POM 的 `-parameters`。
扫描 backend、client-java 的 src 与 scripts 中的手写 Java，不扫描 `target` 生成代码。
类型引用规则额外覆盖手写 Groovy；可单独运行 `node scripts/check-type-imports.mjs`。
脚本自身规格：`node --test scripts/check-coding-standards.test.mjs scripts/type-imports.test.mjs`。
它不验证依赖传递树、JSON 字段等价、锁的真实互斥和事务连接；分别运行依赖树检查、
后端 DTO / MockMvc / 装配 / 锁规格以及现场手册，不能拿一次静态扫描代替验收。
容器判空检查只识别语法上可确定的形态（`Objects.isNull(x) || x.isEmpty()`、`Objects.nonNull(x) && !x.isEmpty()`、`size() == 0`），
且排除 `String`；单独出现的 `list.isEmpty()` 需靠评审或类型感知检查发现。
枚举相等检查依据扫描源码中的枚举声明、import 与显式变量类型，覆盖常量、反向/括号比较及枚举内部比较；
不会根据全大写名称猜测对象类型。无 classpath，无法完整处理变量遮蔽、静态导入、推断类型和只有方法返回值的比较；
扫描通过不等于所有对象比较零遗漏，仍需结合编译、剩余比较审查与相关测试。
类型引用检查屏蔽注释和字符串，识别符合包/类型命名约定的引用，并分析显式导入、同包/同文件声明及已知通配导入。
它不提供完整编译器类型解析（如依赖包通配导入、异常命名或 Groovy 字符串内插表达式）；缩短引用与例外必须结合编译、动态测试及 IDEA 检查验收。
