package com.neocat.alert.infra.jdbc.row;

import lombok.Data;

import java.sql.Timestamp;

/**
 * 告警判定游标行（表 {@code nc_alert_eval_cursor}）。
 *
 * <p>一条规则至多一行：记录窗口基线与最后一次已判定的完整分钟点。
 */
@Data
public class AlertEvalCursorRow {

    private Long id;

    private Long ruleId;

    private Timestamp baselineAt;

    private Timestamp lastEvaluatedMinute;

}
