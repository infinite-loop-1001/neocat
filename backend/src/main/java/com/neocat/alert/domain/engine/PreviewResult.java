package com.neocat.alert.domain.engine;

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
 *
 * fixme: param 找不到
 * @param result    三态结果
 * @param points    逐点判定明细，供界面解释为何不触发
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

    /**
     * 单个分钟点的判定。
     * fixme: param 找不到
     * @param minute      分钟点（epoch millis）
     * @param known       该点是否有可用数据；false 表示缺数
     * @param satisfied   该点条件组合是否满足；缺数点为 false
     * @param missingStat 缺数时缺失的统计项名
     */
    @NamedInterface("alert")
    @Getter
    @EqualsAndHashCode
    @ToString
    public static class PointEvaluation {

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

    public static PreviewResult insufficient(List<PointEvaluation> points) {
        return new PreviewResult(PreviewResultType.INSUFFICIENT_DATA, List.copyOf(points));
    }
}


