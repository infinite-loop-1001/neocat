# 统计数值 BigDecimal 迁移与离线验证

日期：2026-10-08。范围为后端完整统计数值链路，而非仅告警相等判断。

## 计算约定

- `common/DecimalMath` 统一除法与结果舍入：除法 7 位、最终结果 6 位，均为 `HALF_UP`；加减乘不主动截取，不使用 `MathContext` 或精确分数实现。
- `StatCalculator.computeIntermediate` 与内部 `ReportPoints` 返回后续公式输入，不提前做 6 位舍入；`compute`、报表输出、卡片完整公式结果与直接统计告警输入在结果边界做 6 位舍入。
- 分位概率、分箱段内插值、数值观测累加、最后值、排序、统计/环比/明细、卡片 AST、告警引擎/预览、跨模块接口、HTTP DTO 与 JDBC/MyBatis 数值映射使用 `BigDecimal`。
- `Condition.matches`、末值并列比较、机器排序与阈值线比较按数值比较，不按 scale 比较。`1.0` 与 `1.000000` 相等；此处必须使用 `compareTo`，不能套用对象相等规则中的 `Objects.equals`。
- 趋势和环比同桶多行先合并原始分子与分布，再计算；不相加或平均各机器的平均值/失败率/分位。QPS 保留来源行的实际公共覆盖分母。
- 缺数仍为 `null`；卡片缺数优先于除零；子表达式除零向上传播，仍输出 `isUndefined: [{bucketStart, reason: "DIVIDE_BY_ZERO"}]`，没有除零时为 `[]`，缺口仍在 `gaps`。
- 告警阈值必须非空且能精确存入 `DECIMAL(20,6)`，超出范围或有效小数超过 6 位时在领域/HTTP 边界拒绝，不静默截断。

`DecimalMath` 位于 common 模块根包的默认导出接口；相关模块已有 `"common"` 依赖声明，因此没有新增具名接口、模块依赖边或修改导出类型基线。卡片 Convert 改为 Spring 注入的 MapStruct 生成映射，并验证了枚举列表、阈值、空数组与 null 的映射。

## 保留边界与限制

- 原始整数计数、耗时总量/极值、ID、时间戳和直方图段计数仍为整数；本次不扩展原有整数存储范围。
- Protobuf 的 `double` 字段不改；Metric 在分析入口用 `BigDecimal.valueOf` 转换。Heartbeat 整数直接转换，不绕经 double。
- ClickHouse 既有 `Float64` schema 和 SQL 聚合不改，JDBC 用 `getBigDecimal` 进入统计域、数值写入传递 BigDecimal。驱动/列最终浮点存储与数据库 SQL 聚合仍存在精度限制；这里不是任意精度持久化承诺。
- 数值分布保持原整数直方图量化口径和饱和边界，分箱分位仍是估算。BigDecimal 不能还原输入/存储/分箱边界前已丢失的精度。
- JSON 继续输出数字而非字符串，可能有尾随零；前端仍用 JavaScript `number`。高精度阈值通过现有浏览器表单可能失真；后端直接绑定原始 JSON 数字文本不先转 double。
- 没有引入新协议或数据库 schema 迁移；现场读写与 Spring 全量装配未验收，不能据此称为生产就绪。

## 离线证据

| 验证 | 结果 |
|---|---|
| IDEA `build_project` | 成功，无编译错误 |
| IDEA 核心修改文件 `lint_files`（ERROR） | 无错误级问题；不是全仓警告清零 |
| 后端 `mvn -o -f backend/pom.xml test` | 1377 项，10 failures，0 errors，0 skipped |
| 新增 8 个 Decimal 规格 | 39 项全部通过 |
| SDK `mvn -o -f client-java/pom.xml test` | 30 项全部通过 |
| 前端 `npm test` / `npm run flow` / `npm run build` | 104 项、47 个 mock 检查点与构建通过 |
| 后端、SDK `-DskipTests package` | 均成功；不代表后端全量测试通过 |
| 协议漂移检查 | 唯一 proto，19 个生成类两端逐字节一致 |
| 端点 / 错误码检查 | 64 后端端点覆盖 39 前端 mock 端点，44 错误码一致 |
| 编码规范 / 类型引用扫描 | 449 个手写 Java / 569 个 Java+Groovy 文件通过 |
| 检查器自身回归 | 编码规范 9 项、类型引用 9 项全部通过 |
| `git diff --check` | 通过 |

新增规格覆盖除法 7 位、结果 6 位、正负半值舍入、精确中间加减乘、超过 double 精确整数范围的统计/常量、分位插值、scale 无关的六种比较、缺数与嵌套除零、统计→卡片→告警/预览链路、趋势/环比合并、实际 Jackson/MockMvc 绑定、MapStruct 映射、MyBatis DECIMAL 类型处理器和领域/Mapper 行往返。所有数据库证据均为离线 Mock/类型绑定，不是实际 SQL 读写。

后端 10 个失败与迁移前基线逐项按 `(class, name, kind)` 比较一致：`ModuleBoundarySpec` 6 项、`PrdAcceptanceTraceabilitySpec` 4 项。既有组织具名接口/名称问题未顺带修复；仍不能称后端全绿或模块边界已完整验证。

IDE 终端工具超时后 Maven/npm、批量证据汇总与 Git 审计退回本地 shell；文件查询/阅读、Java 编译和静态问题验证使用 IDEA MCP。shell 文本扫描不是 IDE 类型解析证据。

本轮未暂存、提交、推送或调整暂存状态；暂存区二进制 diff SHA-256 保持：

```text
bc1ed19a2f18b97e63cf92bcf602de79201cf9511801a8e0bd7a45ab93619ce7
```

完整日志和逐项基线比对结果位于当前环境的：

```text
/private/var/folders/3r/r3sj_44x3y35_rllf_bblgn40000gn/T/opencode/
  neocat-decimal-backend.log
  neocat-decimal-test-summary.json
  neocat-decimal-sdk.log
  neocat-decimal-backend-package.log
  neocat-decimal-sdk-package.log
```
