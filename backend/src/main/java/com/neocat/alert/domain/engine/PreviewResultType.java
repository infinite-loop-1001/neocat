package com.neocat.alert.domain.engine;

import org.springframework.modulith.NamedInterface;

/**
 * 预告警试算的三态结果（PRD 06 §4）。
 *
 * <ul>
 *   <li>{@link #TRIGGER} 当前会触发；</li>
 *   <li>{@link #NO_TRIGGER} 当前不会触发；</li>
 *   <li>{@link #INSUFFICIENT_DATA} 数据不足。</li>
 * </ul>
 */
@NamedInterface("alert")
public enum PreviewResultType {
    TRIGGER,
    NO_TRIGGER,
    INSUFFICIENT_DATA
}
