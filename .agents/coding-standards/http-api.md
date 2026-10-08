# HTTP API 编码规范

采用兼容方案：`ResponseEntity<专用 DTO>`，不增加 `{code,message,data}` 包装。

- DTO 与 Convert 位于各业务模块 `api/http/dto`、`api/http/convert`，不跨模块复用 HTTP DTO。
- 请求 DTO 仅承载接口数据，不包含 `toRule()`、`toCard()` 等领域转换行为。
- Convert 完成请求到领域参数、领域结果到响应 DTO 的转换；**必须使用 MapStruct**，禁止手写静态转换工具类。
- Spring 管理的 Convert 使用 `@Mapper(componentModel = "spring")`，Controller 等调用方通过构造函数注入，禁止在生产调用方使用 `Mappers.getMapper` 或静态单例替代注入。无 Spring 上下文的离线单测可使用 `Mappers.getMapper` 获取生成实现。
- 普通字段、嵌套 DTO 与集合元素映射交给 MapStruct 生成；字段名差异、默认值与特殊转换通过 `@Mapping` 及明确的辅助方法表达。仅对领域工厂调用、条件分支或自定义解析等无法直接声明的逻辑保留 `default` / 实例辅助方法，禁止只加 `@Mapper` 外壳而仍手写全部字段装配。不得为了生成映射绕过领域工厂或改变聚合不变量。
- MapStruct 处理器必须在构建中显式配置；与 Lombok 同用时配置 `lombok-mapstruct-binding`。生成实现不手工修改。转换改动须用离线测试验证默认值、枚举解析、可空字段与 JSON 结构，不能以编译成功代替契约验证。
- 禁止 Controller 直接暴露领域类、Map 拼装固定字段响应。动态键有实际业务语义时可以作为 DTO 内明确类型的字段。
- 列表与标量保持原 JSON 结构，不为「专用 DTO」给原有数组或标量增加外层对象。
- 统一返回 ResponseEntity，保留 URL、请求参数、状态码、错误码、ApiError、Cookie 与鉴权语义。
- `null`、省略字段、空集合不互换；缺数不得改为 0。
- Protobuf 上报继续使用生成的协议类型及 `ResponseEntity<byte[]>`，不能改为 JSON，协议仍由唯一 proto 生成。
- 当前时间来自公共静态 TimeProvider，不注入 Clock，也不提供设置时钟的方法；Groovy 单测直接给其私有静态时钟字段赋值，结束后赋回系统 UTC 时钟，不提供隔离或恢复作用域。对 DTO 实际执行 Jackson 绑定和序列化测试，不仅检查反射或 Mock 返回值。
