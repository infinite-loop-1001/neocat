package com.neocat.dashboard.domain.formula;

import com.neocat.query.domain.stat.Stat;

import java.util.List;
import java.util.ArrayList;

import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.ToString;
import org.springframework.modulith.NamedInterface;

/**
 * 四则运算。
 */
@NamedInterface("dashboard")
@Getter
@EqualsAndHashCode
@ToString
public final class Binary implements Formula {
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
