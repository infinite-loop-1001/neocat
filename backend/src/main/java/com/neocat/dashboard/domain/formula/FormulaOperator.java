package com.neocat.dashboard.domain.formula;
import org.springframework.modulith.NamedInterface;

/**
 * 公式四则运算符（PRD 05 §4，技术方案 02 §9.2）。
 *
 * <p>{@link #ADD} / {@link #SUBTRACT} 要求两侧单位兼容；
 * {@link #MULTIPLY} / {@link #DIVIDE} 按单位推导规则计算结果单位。
 */
@NamedInterface("dashboard")
public enum FormulaOperator {
    ADD, SUBTRACT, MULTIPLY, DIVIDE
}
