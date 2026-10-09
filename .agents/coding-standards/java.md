# Java 编码规范

## 字段与构造函数

```java
public static volatile int EVALUATE_DELAY_SECONDS;

public static volatile int NOTIFY_TIMEOUT_MS;

private final Map<Long, State> states;

public Evaluator() {
    this.states = new ConcurrentHashMap<>();
}
```

自有 Spring 组件通过构造函数注入，不使用字段注入或 Service Locator。构造函数有重载时只标注一个实际注入构造函数。常量可以在声明处初始化。数据源、第三方构造器、具名多实例与必要工厂允许保留 `@Bean`；Clock 不作为 Bean 注入。Apollo 初始化依赖和 ApplicationReadyEvent 启动时序不得丢失。

## 时间与配置归属

- 后端当前墙上时间统一使用 `com.neocat.common.time.clock.TimeProvider.now()` / `millis()`，禁止生产组件持有或注入 Clock / ClockProvider，禁止调用方直接使用 `Instant.now()`、`System.currentTimeMillis()` 或系统 Clock 工厂取时。公共 TimeProvider 内部实现除外。
- 「当前时刻回退延迟后所在的分钟起点」这类时间表达式属于公共时间能力，使用 `TimeProvider.delayedMinuteStart(delaySeconds)`（返回对齐到分钟边界的 `Instant`）；禁止在调用方手写 `toEpochMilli() / 60_000 * 60_000` 之类的毫秒取整。延迟值仍由调用方从领域配置读取后传入，TimeProvider 不感知业务配置，也不代替「该分钟点是否已完全落库」的判断。
- 事件时间、显式传入的桶边界与平台业务时区保持原语义。SDK 单调计时、超时和耗时测量不改为墙上时钟。
- 整个后端共用 TimeProvider 内一个 `private static volatile Clock` 字段，只暴露取时能力，不提供 setClock 之类的方法。Groovy 单测直接给该私有静态字段赋值：`TimeProvider.clock = Clock.fixed(...)`；测试结束在 cleanup / finally 中赋回 `Clock.systemUTC()`。不使用 ThreadLocal、Scope、嵌套恢复或线程隔离；所有线程看到同一时钟，修改该字段的测试串行运行。
- 领域动态配置位于所属模块的 `config` 包，保留 `public static volatile`、Apollo 键和 Bean 名；禁止放到 common 或保存参数副本。
- 跨模块配置访问通过最小必要具名接口，更新 allowedDependencies 与导出基线。应用根包负责启动装配和 Apollo 校验，校验完整注册领域配置且不引入 common 到业务模块的反向依赖。

## 重复 key

必须显式传入合并函数。以下为拒绝重复的例子：

```java
Collectors.toMap(Item::getId, Function.identity(), (left, right) -> {
    throw new IllegalStateException("重复 ID：" + left.getId());
})
```

累加、按版本取最新等策略需有业务理由与测试。不能为了通过检查统一选择第一个值。Map.entrySet 的来源虽然唯一，也显式声明重复处理策略。

## 类型与依赖

### 一种类型一个文件（强制）

- 适用于 backend、client-java 的手写生产 Java 代码，不限于 HTTP 请求或响应：DTO、领域事件、值对象、内部接口结果与持久化模型等具名 class / interface / enum 应为独立顶层类型，文件名与类型名一致；禁止用 `XxxDtos` 等容器类集中声明多个类型，也不在同一文件并列声明多个顶层类型。
- 例外：私有辅助类（private 嵌套类及其内部实现类型、仅在方法内部使用的局部辅助类）、SDK 的 `com.neocat.client.NeoCat.Builder`、生成代码、匿名类和测试夹具。不得将原本对外使用的类型改为 private 来规避规则。
- 拆分保持所属业务模块和包边界，按需保留 public 或包级可见性；更新调用方、MapStruct、Groovy 测试、反射类名与持久化类型映射的引用。sealed 类型拆分后显式声明 permits，保持原有允许的子类型集合。
- 独立文件不等于在父包平铺：按用途归职责子包，HTTP DTO 按接口用途归组，数据库行模型使用基础设施 `row` 子包，领域返回模型使用所属领域 `result` 子包。关联类型一起归组，沿用已有事件、公式、时间范围等完整类型族；禁止混入跨域通用 `model` 大包，也不为单个类型机械增加层级。
- 字段、构造器、行为、Jackson / Lombok / SpringDoc 注解与 JSON / Protobuf 契约不变，尤其保留显式 `@Schema(name=…)`。具名接口导出基线只能按实际类型迁移更新，不借拆分增加模块依赖或扩大接口职责。
- 迁移必须编译并运行序列化、映射、模块边界及相关动态测试；不能仅靠文本引用替换判定正确。

### 类型引用与 import（强制）

- 手写 Java / Groovy 源码（backend、client-java、测试与 scripts）使用显式 `import` 与简单类名，不在注解、字段、参数、泛型、构造调用、class 字面量、方法引用或静态成员访问中重复书写包路径。
- 唯一例外是同一源文件确有同名类型冲突：优先导入其中一个类型，另一类型保留必要的全限定名；不得以「避免加 import」为由制造例外。嵌套类型可以使用 `Outer.Inner`；存在嵌套类型、类型参数或局部名字遮蔽时必须保证原绑定不变。
- `package` / `import` 声明本身不适用；反射类名、配置键、断言等字符串保持原值，注释中的定位信息不改；MapStruct / Protobuf 等生成代码不手工修改。
- 不改变公开类型、JSON / Protobuf 契约或运行行为；缩短引用后必须编译并运行相关离线测试，不能仅用文本替换结果作为类型绑定正确的证据。

```java
import org.springframework.modulith.NamedInterface;
import org.springframework.stereotype.Component;

@NamedInterface("tree")
@Component
public class LatenessPolicy {
}
```

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

## 容器与相等

容器（`Collection` / `List` / `Set`）与 `Map` 的判空统一使用 Apache `commons-collections4`：

```java
if (CollectionUtils.isEmpty(recipients)) {
    return Lists.newArrayList();
}
if (MapUtils.isNotEmpty(labels)) {
    entries.putAll(labels);
}
List<Line> lines = ListUtils.emptyIfNull(card.getThresholdLines());
```

- 判空/非空用 `CollectionUtils`、`MapUtils`；`ListUtils` / `SetUtils` 没有 `isEmpty`，只有 `emptyIfNull`。
- `emptyIfNull` 按静态类型选择 `ListUtils` / `SetUtils` / `MapUtils`，其余用 `CollectionUtils`。
- 禁止 `list.size() == 0`、`Objects.isNull(list) || list.isEmpty()` 这类手写判空；可直接用 `CollectionUtils.isEmpty()` 表达「null 或空」。
- 空容器默认值统一使用 Guava 可变工厂；禁止空的 `List.of()` / `Set.of()` / `Map.of()`。非空的 `List.of(...)`、`Map.of(...)` 与 `List.copyOf(...)` 如确实需要只读语义可以保留。

### 可变空容器与独立集合

需要空容器时使用 Guava 工厂：

```java
List<Line> lines = Lists.newArrayList();
Set<Long> ids = Sets.newHashSet();
Map<String, String> labels = Maps.newHashMap();
```

该规则覆盖 backend、client-java、手写 Java 测试和 `scripts`；生成代码、Groovy 与前端不适用。源码中任何位置都禁止使用 `subList()`，因为它返回原列表的视图而非独立集合；需要截取时使用索引循环或收集到新的 `ArrayList`，保留顺序、边界和所需的可变性。

判断对象相等使用 `Objects.equals(left, right)`，避免任一比较对象为 null 时抛 NPE。**枚举不例外**：`row.level() == AggregationLevel.DAY`、`scope == AlertScope.ORGANIZATION`、枚举内部的 `this == NUMBER` 一律写成 `Objects.equals(...)`。

```java
if (Objects.equals(row.level(), AggregationLevel.DAY)) {
    return Date.valueOf(row.bucketStart().atZone(zone.get()).toLocalDate());
}
return !Objects.equals(status, IngestStatus.REJECTED);
```

- 只有原始类型保留 `==` / `!=`：`byte` / `short` / `int` / `long` / `float` / `double` / `char` / `boolean` 及其字面量与原始类型常量。
- `Long.MAX_VALUE`、`Integer.MIN_VALUE` 之类包装类常量是数值比较，保留 `==`；`Long` 与 `long` 混用同样是先拆箱的数值比较，保留 `==`。
- Groovy 的 `==` 已等价于 null-safe 的 `equals`，不适用本条（Java 专用规则，见上文判空章节的适用范围）。
- 注意 `a.equals(b)` 改为 `Objects.equals(a, b)` 会把原本的 NPE 变成返回 false；接收者确定非 null 时保持原样更安全。

容器判空工具仅 backend 声明 `commons-collections4`；Guava 在 backend 与 client-java 均显式声明，用于统一的可变空容器与独立集合创建。

正常未找到：返回 `@Nullable T` 并用 `Objects.isNull` / `Objects.nonNull` 消费。必需存在：明确抛出已有业务异常。集合返回空集合，不返回 null。Optional 不是避免制定未找到语义的替代品；框架的 Optional 可局部消费。

现有明确的 equals、isEmpty、isBlank 无需为了风格改成复杂工具调用。工具限定 JDK、Apache，避免增加依赖或掩盖领域语义。
