# Problem 下钻趋势

用户已确认：Problem 的每个 Name 可点进趋势页，与 Transaction/Event 一致（含日/周/月环比多选）。

- 新增路由 `svc/:service/problem/:type/:name`，复用 `SeriesView`；`:type` 为后端原始类别枚举。
- Problem 直接复用通用 `/reports/series` 与 `/reports/samples`，`kind=PROBLEM`；契约中的 `/reports/problem/series` 后端未实现，不新增接口。
- 统计项按 PRD 03 §9：`EXCEPTION` 只提供次数类与 QPS，不出现耗时与分位；`SLOW_*` 额外提供耗时与分位。
- 沿用环比多选、时间范围、超过当前时间不可继续向后翻页等既有行为。
- 保留机器（实例）筛选；机器列表仍按服务实例拉取。
- 返回入口指向 Problem 页。
- 只改前端并复用现有 mock；验证前端单测、mock 动线检查与构建，浏览器功能测试由用户完成。
