package com.neocat.dashboard.domain.formula;

import java.math.BigDecimal;

import com.google.common.collect.Lists;
import com.neocat.query.domain.stat.Stat;

import java.util.List;

import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.ToString;
import org.springframework.modulith.NamedInterface;

/**
 * 常数。
 */
@NamedInterface("dashboard")
@Getter
@EqualsAndHashCode
@ToString
public final class Constant implements Formula {
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
