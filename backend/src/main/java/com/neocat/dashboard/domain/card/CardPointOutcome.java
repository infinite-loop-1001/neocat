package com.neocat.dashboard.domain.card;
import org.springframework.modulith.NamedInterface;

/**
 * 卡片单桶求值结果（PRD 05 §5、技术方案 02 §9.2）。
 *
 * <ul>
 *   <li>{@link #OK} 正常计算出数值；</li>
 *   <li>{@link #GAP} 任一输入缺数，**不当作 0**、**不沿用上一点**；</li>
 *   <li>{@link #DIVIDE_BY_ZERO} 分母为 0，显示「不可计算」。</li>
 * </ul>
 */
@NamedInterface("dashboard")
public enum CardPointOutcome {
    OK,
    GAP,
    DIVIDE_BY_ZERO
}
