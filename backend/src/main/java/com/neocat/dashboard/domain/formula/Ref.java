package com.neocat.dashboard.domain.formula;

import com.google.common.collect.Lists;
import com.neocat.query.domain.stat.Stat;

import java.util.List;

import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.ToString;
import org.springframework.modulith.NamedInterface;

/**
 * 裸统计项。
 */
@NamedInterface("dashboard")
@Getter
@EqualsAndHashCode
@ToString
public final class Ref implements Formula {
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
        return Lists.newArrayList(stat);
    }
}
