package com.neocat.alert.domain.engine.result;

import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.ToString;
import org.springframework.modulith.NamedInterface;

import java.util.List;

/**
 * 预告警试算结果（PRD 06 §4）。
 *
 * <p>结果只有三种：
 * <ul>
 *   <li>{@link PreviewResultType#TRIGGER} 当前会触发；</li>
 *   <li>{@link PreviewResultType#NO_TRIGGER} 当前不会触发；</li>
 *   <li>{@link PreviewResultType#INSUFFICIENT_DATA} 数据不足。</li>
 * </ul>
 *
 * <p>**预告警只展示，不发送、不留历史、不改变规则状态**（PRD 06 §4）。
 * <p>
 * fixme: param 找不到
 *
 * <p>result：三态结果。
 * <p>points：逐点判定明细，供界面解释为何不触发。
 */
@NamedInterface("alert")
@Getter
@EqualsAndHashCode
@ToString
public class PreviewResult {

    private final PreviewResultType result;

    private final List<PointEvaluation> points;

    public PreviewResult(PreviewResultType result, List<PointEvaluation> points) {
        this.result = result;
        this.points = points;
    }

    public static PreviewResult insufficient(List<PointEvaluation> points) {
        return new PreviewResult(PreviewResultType.INSUFFICIENT_DATA, List.copyOf(points));
    }
}
