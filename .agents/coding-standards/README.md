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

## 建议

1. Spring Bean 使用构造函数注入；实例运行时字段初始化放进构造函数。自有组件优先 `@Service` / `@Component`，不为简单构造专设 Wiring。
2. 不使用 Lombok `@UtilityClass`，工具类用显式私有构造器。
3. 自有函数不返回 `Optional`；可空查询明确标注可空，必需查询抛明确业务异常。JDK / 框架返回的 Optional 立即消费，禁止返回 null 的 Optional。
4. 集合辅助操作优先 JDK 或 Apache 工具类；Java 判空遵守上述强制项。不为简单操作新增依赖，不使用 Hutool、Fastjson 或 Spring 的 CollectionUtils 替代。

执行细节及示例见 [java.md](java.md)。离线测试不代表真实中间件验收通过。

## 自动回归

仓库根运行 `node scripts/check-coding-standards.mjs`（需要 JDK 17+）：源码禁用项、
JDK 语法树解析成员间距/重复 key/Optional 返回声明/null 比较、两个 POM 的 `-parameters`。
扫描 backend、client-java 的 src 与 scripts 中的手写 Java，不扫描 `target` 生成代码。脚本自身规格：
`node --test scripts/check-coding-standards.test.mjs`。
它不验证依赖传递树、JSON 字段等价、锁的真实互斥和事务连接；分别运行依赖树检查、
后端 DTO / MockMvc / 装配 / 锁规格以及现场手册，不能拿一次静态扫描代替验收。
