-- 告警窗口状态由「每个满足条件的分钟点一行」改为「每条规则一行判定游标」，
-- 表名由 nc_alert_window_state 改为 nc_alert_eval_cursor。
-- 人工执行一次后再部署后端。现场表为空时直接改结构；若已有旧窗口点，先清空再改，
-- 旧点无法映射到新游标，启用后会从新基线重建。
-- MySQL 8 不支持 DROP COLUMN IF EXISTS，本脚本不幂等，重复执行会报错即中止。
--
-- 影响：nc_alert_window_state → nc_alert_eval_cursor。不保留旧窗口点。

DELETE FROM nc_alert_window_state;

ALTER TABLE nc_alert_window_state
    DROP INDEX uk_nc_alert_window_state_rule_minute,
    DROP COLUMN point_minute,
    DROP COLUMN satisfied,
    ADD COLUMN baseline_at DATETIME(3) NOT NULL COMMENT '窗口基线（启用/编辑/补收件人时刻，毫秒）' AFTER rule_id,
    ADD COLUMN last_evaluated_minute DATETIME(3) NULL COMMENT '最后一次已判定的完整分钟点；空表示基线刚建立、尚未判定' AFTER baseline_at,
    ADD UNIQUE KEY uk_nc_alert_eval_cursor_rule (rule_id),
    RENAME TO nc_alert_eval_cursor,
    COMMENT = '告警判定游标；一条规则一行，启用/编辑/补人时清空后从新基线重建';
