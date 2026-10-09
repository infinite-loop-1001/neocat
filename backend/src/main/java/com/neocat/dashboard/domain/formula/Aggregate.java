package com.neocat.dashboard.domain.formula;

import com.clickhouse.client.internal.google.common.collect.Lists;
import com.neocat.query.domain.stat.Stat;

import java.util.List;

import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.ToString;
import org.springframework.modulith.NamedInterface;

/**
 * 聚合函数包裹。
 */
@NamedInterface("dashboard")
@Getter
@EqualsAndHashCode
@ToString
public final class Aggregate implements Formula {
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
        return Lists.newArrayList(stat);
    }
}
