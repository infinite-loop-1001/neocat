package com.neocat.dashboard.domain.formula;

import java.math.BigDecimal;

import com.google.common.collect.Lists;
import com.neocat.query.domain.stat.Stat;

import java.util.List;
import java.util.ArrayList;

import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.ToString;
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
public sealed interface Formula {

    /**
     * 单位推导结果。
     */
    Unit unit();

    /**
     * 公式引用到的统计项；用于建立卡片与告警目标的依赖关系。
     */
    List<Stat> referencedStats();

    /**
     * 四则运算。
     */
    @NamedInterface("dashboard")
    @Getter
    @EqualsAndHashCode
    @ToString
    final class Binary implements Formula {
        private final FormulaOperator op;

        private final Formula left;

        private final Formula right;

        public Binary(FormulaOperator op, Formula left, Formula right) {
            this.op = op;
            this.left = left;
            this.right = right;
        }

        @Override
        public Unit unit() {
            return switch (op) {
                case ADD, SUBTRACT -> left.unit();
                case MULTIPLY -> left.unit().multiply(right.unit());
                case DIVIDE -> left.unit().divide(right.unit());
            };
        }

        @Override
        public List<Stat> referencedStats() {
            List<Stat> all = new ArrayList<>(left.referencedStats());
            all.addAll(right.referencedStats());
            return List.copyOf(all);
        }
    }

    /**
     * 聚合函数包裹。
     */
    @NamedInterface("dashboard")
    @Getter
    @EqualsAndHashCode
    @ToString
    final class Aggregate implements Formula {
        private final FormulaAggregate agg;

        private final Stat stat;

        public Aggregate(FormulaAggregate agg, Stat stat) {
            this.agg = agg;
            this.stat = stat;
        }

        @Override
        public Unit unit() {
            return Unit.of(stat);
        }

        @Override
        public List<Stat> referencedStats() {
            return List.of(stat);
        }
    }

    /**
     * 裸统计项。
     */
    @NamedInterface("dashboard")
    @Getter
    @EqualsAndHashCode
    @ToString
    final class Ref implements Formula {
        private final Stat stat;

        public Ref(Stat stat) {
            this.stat = stat;
        }


        @Override
        public Unit unit() {
            return Unit.of(stat);
        }

        @Override
        public List<Stat> referencedStats() {
            return List.of(stat);
        }
    }

    /**
     * 常数。
     */
    @NamedInterface("dashboard")
    @Getter
    @EqualsAndHashCode
    @ToString
    final class Constant implements Formula {
        private final BigDecimal value;

        public Constant(BigDecimal value) {
            this.value = value;
        }


        @Override
        public Unit unit() {
            return Unit.NUMBER;
        }

        @Override
        public List<Stat> referencedStats() {
            return Lists.newArrayList();
        }
    }
}