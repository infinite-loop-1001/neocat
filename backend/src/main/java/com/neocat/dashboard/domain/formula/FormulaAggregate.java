package com.neocat.dashboard.domain.formula;

/**
 * 公式聚合函数（PRD 05 §4，技术方案 02 §9.2）。
 *
 * <p>单桶内聚合退化为取值本身：卡片以桶为单位，聚合已由查询层完成。
 */
@org.springframework.modulith.NamedInterface("dashboard")
public enum FormulaAggregate {
    SUM, AVG, MIN, MAX
}
