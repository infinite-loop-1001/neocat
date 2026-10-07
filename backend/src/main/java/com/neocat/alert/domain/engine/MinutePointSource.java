package com.neocat.alert.domain.engine;

import com.neocat.alert.domain.rule.AlertTarget;
import com.neocat.query.domain.stat.Stat;
import org.springframework.modulith.NamedInterface;

import java.util.List;
import java.util.Map;

/**
 * 分钟点数据源（PRD 06 §4、§5）。
 *
 * <p>由 query 模块实现；alert 只依赖该抽象。
 * 每个点返回该规则目标序列在该分钟的值：值为 {@code null} 表示**缺数**（未知），
 * 与「确认无调用」的 0 严格区分。
 */
@NamedInterface("alert")
public interface MinutePointSource {

    /**
     * 读取指定分钟点的统计项值。
     *
     * @param target 告警目标
     * @param minute 分钟点（epoch millis）
     * @param stats  需要的统计项
     * @return 各统计项的值；缺数项为 null 或不在映射中
     */
    Map<Stat, Double> values(AlertTarget target, long minute, List<Stat> stats);
}
