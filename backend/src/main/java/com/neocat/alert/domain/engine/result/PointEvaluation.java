package com.neocat.alert.domain.engine.result;

import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.ToString;
import org.springframework.modulith.NamedInterface;

/**
 * 单个分钟点的判定。
 * fixme: param 找不到
 *
 * <p>minute：分钟点（epoch millis）。
 * <p>known：该点是否有可用数据；false 表示缺数。
 * <p>satisfied：该点条件组合是否满足；缺数点为 false。
 * <p>missingStat：缺数时缺失的统计项名。
 */
@NamedInterface("alert")
@Getter
@EqualsAndHashCode
@ToString
public class PointEvaluation {

    private final long minute;

    private final boolean known;

    private final boolean satisfied;

    private final String missingStat;

    public PointEvaluation(long minute, boolean known, boolean satisfied, String missingStat) {
        this.minute = minute;
        this.known = known;
        this.satisfied = satisfied;
        this.missingStat = missingStat;
    }

}
