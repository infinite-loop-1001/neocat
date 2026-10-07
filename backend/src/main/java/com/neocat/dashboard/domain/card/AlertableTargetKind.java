package com.neocat.dashboard.domain.card;

/**
 * 可作为组织告警目标的对象类型（PRD 05 §9、PRD 06 §8）。
 *
 * <ul>
 *   <li>{@link #RAW_STAT}：卡片引用的**原始统计项**；</li>
 *   <li>{@link #CARD_RESULT}：卡片的**计算结果**。</li>
 * </ul>
 */
@org.springframework.modulith.NamedInterface("dashboard")
public enum AlertableTargetKind {
    RAW_STAT,
    CARD_RESULT
}
