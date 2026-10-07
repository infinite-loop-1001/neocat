package com.neocat.dashboard.domain.card;

import java.util.List;

/**
 * 卡片求值结果（PRD 05 §5、技术方案 02 §9.2）。
 *
 * <p>关键语义（PRD 05 §5）：
 * <ul>
 *   <li>任意输入在该桶缺数 → 结果为**缺口**；</li>
 *   <li>**不把缺数当 0**；</li>
 *   <li>**不沿用上一点**；</li>
 *   <li>Tooltip 显示缺失的输入；</li>
 *   <li>除零显示「不可计算」。</li>
 * </ul>
 *
 * @param bucketStart 桶起点
 * @param bucketEnd   桶终点
 * @param value       值；null 表示缺口或不可计算
 * @param outcome     OK / GAP / DIVIDE_BY_ZERO
 * @param missingInputs 缺口时缺失的输入（统计项名）
 */
@org.springframework.modulith.NamedInterface("dashboard")
@lombok.Getter
@lombok.EqualsAndHashCode
@lombok.ToString
public class CardPoint {
    private final long bucketStart;

    private final long bucketEnd;

    private final Double value;

    private final CardPointOutcome outcome;

    private final List<String> missingInputs;

    public CardPoint(long bucketStart, long bucketEnd, Double value, CardPointOutcome outcome, List<String> missingInputs) {
        this.bucketStart = bucketStart;
        this.bucketEnd = bucketEnd;
        this.value = value;
        this.outcome = outcome;
        this.missingInputs = missingInputs;
    }

    public boolean gap() {
        return outcome == CardPointOutcome.GAP;
    }
    public boolean undefined() {
        return outcome == CardPointOutcome.DIVIDE_BY_ZERO;
    }
}


