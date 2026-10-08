package com.neocat.alert.domain.rule;
import org.springframework.modulith.NamedInterface;

/**
 * 告警目标类型（PRD 06 §1、§8）。
 *
 * <ul>
 *   <li>{@link #RAW_METRIC} 原始指标目标：`服务 + 报表类型 + 指标对象 + 统计项`；</li>
 *   <li>{@link #CARD_RESULT} 卡片结果目标：引用组织大盘中某张卡片的当前计算结果。</li>
 * </ul>
 */
@NamedInterface("alert")
public enum AlertTargetKind {
    RAW_METRIC,
    CARD_RESULT
}
