# 错误模型改造（2026-10-01）

## 约束

- 业务异常都是抽象基类 `NeocatException` 的具体子类；按语义分别表示校验、认证、权限、缺失、冲突、过期、业务规则及上报异常。错误类别只由异常子类表达，`ErrorCode` 不重复记录类别。
- `ErrorCode` 是唯一异常码目录：10001 通用、20001 身份/权限、30001 组织、40001 大盘/公式、50001 告警、60001 上报、70001 平台、80001 Trace；每段保留其余编号供扩展。代码与文案模板在枚举中定义；参数由异常构造器填充。`fromCode` 不接受未知编号。
- `ErrorCodeMapping` 逐码映射 HTTP 状态，不根据万位或异常类型推断。
- REST 错误仍为 `{code,message}`，其中 `code` 从旧字符串改为 JSON 数字；Protobuf v1 的 `code` 保持 string 字段，异常编号写为十进制数字字符串。非异常结果标识 `OK`、`DUPLICATE`、`QUEUE_FULL` 不变。
- 原跨域 `NOT_FOUND`、`FORBIDDEN` 拆为对应业务域的具体码；框架抛出的通用 NoSuchElementException 仍使用通用 `NOT_FOUND`。消息包含敏感信息的兜底异常仍只返回固定内部错误文案。

## 验收

Spock 测试验证码值唯一、区间、模板参数数量、类别构造限制、REST JSON 数字及 Protobuf 数字字符串；前端 mock 与真实错误解析一致。真实中间件联调仍依 `09-integration-checklist.md` 完成。
