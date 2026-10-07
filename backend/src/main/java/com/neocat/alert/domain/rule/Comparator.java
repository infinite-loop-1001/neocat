package com.neocat.alert.domain.rule;

/**
 * 比较符（PRD 06 §2）。
 */
@org.springframework.modulith.NamedInterface("alert")
public enum Comparator {
    GT,
    GTE,
    LT,
    LTE,
    EQ,
    NEQ
}
