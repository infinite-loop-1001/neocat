# Java 编码规范

## 字段与构造函数

```java
public static volatile int EVALUATE_DELAY_SECONDS;

public static volatile int NOTIFY_TIMEOUT_MS;

private final Map<Long, State> states;

public Evaluator(Clock clock) {
    this.clock = Objects.requireNonNull(clock);
    this.states = new ConcurrentHashMap<>();
}
```

自有 Spring 组件通过构造函数注入，不使用字段注入或 Service Locator。构造函数有重载时只标注一个实际注入构造函数。常量可以在声明处初始化。数据源、Clock、第三方构造器、具名多实例与必要工厂允许保留 `@Bean`；Apollo 初始化依赖和 ApplicationReadyEvent 启动时序不得丢失。

## 重复 key

必须显式传入合并函数。以下为拒绝重复的例子：

```java
Collectors.toMap(Item::getId, Function.identity(), (left, right) -> {
    throw new IllegalStateException("重复 ID：" + left.getId());
})
```

累加、按版本取最新等策略需有业务理由与测试。不能为了通过检查统一选择第一个值。Map.entrySet 的来源虽然唯一，也显式声明重复处理策略。

## 类型与依赖

- 禁止声明 `record`，使用普通类和 `getX()` / `isX()`；手写行为方法不强制改名。
- 禁止 Hutool、Fastjson、Fastjson2，包括直接和可排除的传递依赖。
- 禁止新增 `@UtilityClass`；普通工具类使用显式私有构造器。Convert / Converter 必须使用 MapStruct，不得作为手写静态工具类实现；Spring 注入与特殊映射边界见 [http-api.md](http-api.md)。
- DTO 不自动输出密码、token 等敏感字段的 toString。
- 两个 POM 的 `-parameters` 必须保留，新增请求 DTO 需实测 JSON 绑定。

## 可空返回值与工具

Java 判空为强制规范：使用 `Objects.isNull(value)` / `Objects.nonNull(value)`，禁止
`value == null`、`value != null`、`null == value`、`null != value`，包括带括号的 null。
适用于生产代码、手写 Java 测试和 Java 检查脚本；不手工修改 MapStruct / Protobuf 等生成代码，
也不将 Java 专用规则套用于 Groovy 或前端。使用 `java.util.Objects`，允许标准方法引用。

```java
if (Objects.isNull(value)) {
    return fallback;
}
return Objects.nonNull(value) && value.isValid();
```

复合条件与三元表达式同样遵守此规则。替换必须保留原有求值次数、短路顺序、异常与默认值。
`Objects.requireNonNull` 用于必需参数校验，不得替换成普通判空而丢失失败语义；
非 null 的对象相等性比较不受本条影响。不为判空引入依赖。

正常未找到：返回 `@Nullable T` 并用 `Objects.isNull` / `Objects.nonNull` 消费。必需存在：明确抛出已有业务异常。集合返回空集合，不返回 null。Optional 不是避免制定未找到语义的替代品；框架的 Optional 可局部消费。

现有明确的 equals、isEmpty、isBlank 无需为了风格改成复杂工具调用。工具限定 JDK、Apache，避免增加依赖或掩盖领域语义。
