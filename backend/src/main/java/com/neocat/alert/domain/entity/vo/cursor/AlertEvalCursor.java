package com.neocat.alert.domain.entity.vo.cursor;

import lombok.*;
import org.springframework.modulith.NamedInterface;


/**
 * 该类记录当前某一个告警规则当前计算的时间游标位置
 * 即当前 ruleId 规则从 baselineAt 时间点开始判断是否告警
 * 且最后一次判定时间为 lastEvaluatedMinute
 */
@NamedInterface("alert")
@Getter
@Setter
@EqualsAndHashCode
@ToString
@AllArgsConstructor
@NoArgsConstructor
public class AlertEvalCursor {

    /**
     * 告警窗口记录 Id
     */
    private Long id;

    /**
     * 规则 Id
     */
    private Long ruleId;

    /**
     * 窗口基线（启用/编辑/补人时刻）；早于该时刻的点被忽略
     */
    private Long baselineAt;

    /**
     * 开始启动监控开始到现在, 最后判定时间点
     */
    private Long lastEvaluatedMinute;

    public static AlertEvalCursor of(Long ruleId, Long baselineAt, Long curMinute) {
        AlertEvalCursor evalCursor = new AlertEvalCursor();
        evalCursor.setRuleId(ruleId);
        evalCursor.setBaselineAt(baselineAt);
        evalCursor.setLastEvaluatedMinute(curMinute);
        return evalCursor;
    }

}
