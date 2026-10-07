# HTTP API 编码规范

采用兼容方案：`ResponseEntity<专用 DTO>`，不增加 `{code,message,data}` 包装。

- DTO 与 Convert 位于各业务模块 `api/http/dto`、`api/http/convert`，不跨模块复用 HTTP DTO。
- 请求 DTO 仅承载接口数据，不包含 `toRule()`、`toCard()` 等领域转换行为。
- Convert 完成请求到领域参数、领域结果到响应 DTO 的转换；可以手写或使用 MapStruct。
- 禁止 Controller 直接暴露领域类、Map 拼装固定字段响应。动态键有实际业务语义时可以作为 DTO 内明确类型的字段。
- 列表与标量保持原 JSON 结构，不为「专用 DTO」给原有数组或标量增加外层对象。
- 统一返回 ResponseEntity，保留 URL、请求参数、状态码、错误码、ApiError、Cookie 与鉴权语义。
- `null`、省略字段、空集合不互换；缺数不得改为 0。
- Protobuf 上报继续使用生成的协议类型及 `ResponseEntity<byte[]>`，不能改为 JSON，协议仍由唯一 proto 生成。
- 时间来自注入 Clock。对 DTO 实际执行 Jackson 绑定和序列化测试，不仅检查反射或 Mock 返回值。
