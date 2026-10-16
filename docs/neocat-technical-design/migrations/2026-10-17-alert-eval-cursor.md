# 告警窗口状态改为判定游标部署说明

1. **先人工执行** `2026-10-17-alert-eval-cursor.sql`，再部署后端。脚本会清空旧表后改名改结构，
   不自动执行，也不幂等。
2. 全新环境直接用更新后的 `../05-mysql-schema.sql`，不要执行本脚本。

## 变更内容

- 表名：`nc_alert_window_state` → `nc_alert_eval_cursor`。
- 旧语义：每个满足条件的完整分钟点一行（`point_minute`、`satisfied`），同一规则可有多行。
- 新语义与 `AlertEvalCursor` 一致：每条规则至多一行。
  - `baseline_at`：窗口基线，早于该时刻的点不参与判定。
  - `last_evaluated_minute`：最后一次已判定的完整分钟点；为空表示基线刚建立、还没有判定过任何分钟。
- 唯一键由 `(rule_id, point_minute)` 改为 `rule_id`，索引名为 `uk_nc_alert_eval_cursor_rule`。

## 执行要点

- 旧窗口点无法对应到「基线 + 最后判定分钟」，脚本先 `DELETE` 再改列、改名。
  部署后已启用规则需要重新启用，窗口从新基线重建。
- 规则上的 `nc_alert_rule.state_since` 仍是规则启用时刻，不由本表替代。
