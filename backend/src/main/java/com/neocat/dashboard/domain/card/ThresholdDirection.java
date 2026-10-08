package com.neocat.dashboard.domain.card;
import org.springframework.modulith.NamedInterface;

/**
 * 阈值线方向（PRD 05 §7，技术方案 02 §9.1）。
 *
 * <p>只用于视觉对照，不自动修改已有告警规则的比较阈值。
 */
@NamedInterface("dashboard")
public enum ThresholdDirection {
    ABOVE,
    BELOW
}
