# 卡片序列 isUndefined 字段统一

用户已选择方案 B：统一生产端、HTTP DTO、前端和契约文档中的卡片序列字段为
`isUndefined`，不恢复旧 `undefined` 键，不同时输出两套键。

## 范围与语义

- `GET /api/cards/{cardId}/series` 的 `isUndefined` 是数组，不是布尔值；元素仍为
  `{bucketStart, reason: "DIVIDE_BY_ZERO"}`，没有除零点时输出 `[]`。
- `CardSeriesService.describe` 生成该键，DashboardConvert 读取该键，SeriesResponse
  用同名字段输出 JSON。缺数仍进入 `gaps`，除零与缺数的 `value` 仍为 `null`。
- 前端当前仅使用卡片序列的 `points`；补充其读取类型中的 `isUndefined`，mock
  响应同步输出该字段。当前 mock 不计算真实公式除零，不捏造除零点，返回空数组。
- 只调整卡片序列契约；不替换 JavaScript 的 `undefined`、其他模型、协议或领域
  `isUndefined()` 的判定逻辑，也不顺带重构转换器。
- 此为用户明确批准的 JSON 字段变更，旧键消费者须同步升级。更新技术契约与现场
  验收要求；不暂存、不提交、不推送。

## 验证计划

- 后端实际执行生产模型 → DTO → Jackson，覆盖空数组、非空除零数组、缺数与真实
  零值，断言新键存在而旧键不存在。
- 前端用 mock 路由回归锁定新键，并跑单测、动线、类型检查与构建。
- 后端离线完整测试对照既有 10 项失败基线；运行端点、错误码、编码规范检查。
- 所有证据均为离线，不代表真实中间件或现场启动验证。

## 已验证证据

- 新规格先在未对齐实现上失败：后端缺失模型键引发 NPE/字段断言失败，前端 mock
  缺少 `isUndefined` 的断言失败；统一后相关后端 53/53、前端新增规格 1/1 通过。
- 后端 `mvn -o -f backend/pom.xml clean test`：1338 项、10 failures、0 errors、
  0 skipped。XML 中失败身份与原 10 项基线逐项一致，无新增或消失；
  HttpDtoContractSpec 21/21、CardTargetSpec 27/27、TimeProviderSpec 5/5 通过。
- 前端 `npm test` 104/104，`npm run flow` 47 个检查点，`npm run build`（含
  vue-tsc）通过。mock 只验证新字段及空数组，不代表真实公式除零已在前端验收。
- 端点检查 64 个后端 / 39 个前端 mock 端点、44 个错误码对齐；编码规范扫描
  448 个 Java 文件 / 两个 POM、类型扫描 560 个 Java / Groovy 文件通过；检查器
  自身规格 15/15 通过。
- 自审确认只更改卡片序列键，领域除零判定、点值与缺口语义不变；没有新增
  转换器或修改其他转换逻辑，没有旧键兼容别名，也未批量替换 JS 的 undefined。
- 完整后端日志：`/private/var/folders/3r/r3sj_44x3y35_rllf_bblgn40000gn/T/opencode/neocat-is-undefined-full.log`。
