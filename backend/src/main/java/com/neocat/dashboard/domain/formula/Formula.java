package com.neocat.dashboard.domain.formula;

import com.neocat.query.domain.stat.Stat;

import java.util.List;

import org.springframework.modulith.NamedInterface;

/**
 * 公式 AST（PRD 05 §4，技术方案 02 §9.2）。
 *
 * <p>支持的文法：
 * <pre>
 * expr   := term (('+'|'-') term)*
 * term   := factor (('*'|'/') factor)*
 * factor := 'sum'|'avg'|'min'|'max' '(' stat ')' | stat | number | '(' expr ')'
 * stat   := hits | failures | failureRate | qps | avgDuration | min | max | tp50..tp9999
 * </pre>
 *
 * <p>不支持：自由脚本、条件表达式、跨服务公式、跨 Name 公式（PRD 05 §4）。
 */
@NamedInterface("dashboard")
public sealed interface Formula permits Binary, Aggregate, Ref, Constant {

    /**
     * 单位推导结果。
     */
    Unit unit();

    /**
     * 公式引用到的统计项；用于建立卡片与告警目标的依赖关系。
     */
    List<Stat> referencedStats();

}