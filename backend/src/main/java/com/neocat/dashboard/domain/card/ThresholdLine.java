package com.neocat.dashboard.domain.card;

import java.math.BigDecimal;
import java.util.Objects;

import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.ToString;
import org.springframework.modulith.NamedInterface;

/**
 * 阈值线（PRD 05 §7，技术方案 02 §9.1）。
 *
 * <p>阈值线是卡片的**可视化配置**：
 * <ul>
 *   <li>可以在图上添加高于/低于的阈值线；</li>
 *   <li>默认只用于视觉对照；</li>
 *   <li>作用于卡片**全部机器聚合结果**；</li>
 *   <li>机器下钻**不自动变成逐机器阈值线**；</li>
 *   <li>阈值线修改**不自动修改已有告警规则的比较阈值**。</li>
 * </ul>
 */
@NamedInterface("dashboard")
@Getter
@EqualsAndHashCode
@ToString
public class ThresholdLine {
    private final ThresholdDirection direction;

    private final BigDecimal value;

    public ThresholdLine(ThresholdDirection direction, BigDecimal value) {
        this.direction = direction;
        this.value = Objects.requireNonNull(value, "value");
    }
}
