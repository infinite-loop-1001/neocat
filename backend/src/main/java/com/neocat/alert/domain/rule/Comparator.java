package com.neocat.alert.domain.rule;
import org.springframework.modulith.NamedInterface;

/**
 * 比较符（PRD 06 §2）。
 */
// question: 可以不自己搞 DSL 吗? 直接用 groovy 脚本
@NamedInterface("alert")
public enum Comparator {
    GT,
    GTE,
    LT,
    LTE,
    EQ,
    NEQ
}
