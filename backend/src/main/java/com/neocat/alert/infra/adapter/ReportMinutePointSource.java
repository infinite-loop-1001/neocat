package com.neocat.alert.infra.adapter;

import java.math.BigDecimal;

import com.neocat.common.DecimalMath;

import com.google.common.collect.Lists;
import com.neocat.alert.domain.rule.AlertTarget;
import com.neocat.alert.domain.engine.MinutePointSource;
import com.neocat.dashboard.api.internal.CardResults;
import com.neocat.query.api.internal.ReportPoints;
import com.neocat.query.domain.stat.Stat;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import org.springframework.beans.factory.annotation.Autowired;

@Component
public class ReportMinutePointSource implements MinutePointSource {
    private final ReportPoints reports;

    private final CardResults cards;

    public ReportMinutePointSource(ReportPoints reports) {
        this(reports, null);
    }

    @Autowired
    public ReportMinutePointSource(ReportPoints reports, CardResults cards) {
        this.reports = reports;
        this.cards = cards;
    }

    @Override
    public Map<Stat, BigDecimal> values(AlertTarget target, long minute, List<Stat> stats) {
        Instant from = Instant.ofEpochMilli(minute);
        if (target.isCardResult()) {
            BigDecimal value = cards.value(target.getCardId(), from, from.plusSeconds(60));
            Map<Stat, BigDecimal> result = new LinkedHashMap<>();
            for (Stat stat : stats) {
                result.put(stat, value);
            }
            return result;
        }
        String name = Objects.equals("METRIC", target.getReportKind()) ? target.getMetricLabels() : target.getName();
        Map<String, BigDecimal> raw = reports.values(target.getReportKind(), target.getService(), target.getType(),
                name, from, from.plusSeconds(60), stats.stream().map(Enum::name).toList(), Lists.newArrayList());
        Map<Stat, BigDecimal> result = new LinkedHashMap<>();
        for (Stat stat : stats) {
            result.put(stat, DecimalMath.result(raw.get(stat.name())));
        }
        return result;
    }
}
