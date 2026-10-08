package com.neocat.alert.domain.rule;
import org.springframework.modulith.NamedInterface;

/**
 * 条件连接符（PRD 06 §2）。
 *
 * <p>**统一 AND 或统一 OR**：不支持单条规则内混用，
 * 也不支持任意嵌套括号表达式。
 */
@NamedInterface("alert")
public enum Combinator {
    AND,
    OR
}
