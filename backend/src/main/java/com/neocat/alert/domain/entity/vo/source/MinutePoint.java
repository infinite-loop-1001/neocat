package com.neocat.alert.domain.entity.vo.source;

import com.neocat.query.domain.stat.Stat;
import java.math.BigDecimal;
import java.util.Map;

import lombok.AllArgsConstructor;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.ToString;
import org.springframework.modulith.NamedInterface;

/**
 * 数据源返回的单个分钟点：查询时间点 + 该点各统计项的取值（PRD 06 §4、§5）。
 *
 * <p>minute：分钟点（epoch millis）。
 * <p>values：该点的统计项取值；缺数项为 {@code null} 或不在映射中，
 * 与「确认无调用」的 0 严格区分。
 */
@NamedInterface("alert")
@Getter
@EqualsAndHashCode
@ToString
@AllArgsConstructor
public class MinutePoint {

    /**
     * 分钟点
     */
    private final Long minute;

    /**
     * 该点的统计项取值
     */
    private final Map<Stat, BigDecimal> values;

}
