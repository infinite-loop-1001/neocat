package com.neocat.alert.infra.adapter;

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

@Component
public class ReportMinutePointSource implements MinutePointSource {
    private final ReportPoints reports;

    private final CardResults cards;

    public ReportMinutePointSource(ReportPoints reports) {
        this(reports, null);
    }
    @org.springframework.beans.factory.annotation.Autowired
    public ReportMinutePointSource(ReportPoints reports, CardResults cards) {
        this.reports = reports;
        this.cards = cards;
    }
    @Override
    public Map<Stat, Double> values(AlertTarget target, long minute, List<Stat> stats) {
        Instant from = Instant.ofEpochMilli(minute);
        if (target.isCardResult()) {
            Double value = cards.value(target.getCardId(), from, from.plusSeconds(60));
            Map<Stat, Double> result = new LinkedHashMap<>();
            for (Stat stat : stats) {
                result.put(stat, value);
            }
            return result;
        }
        String name = "METRIC".equals(target.getReportKind()) ? target.getMetricLabels() : target.getName();
        Map<String, Double> raw = reports.values(target.getReportKind(), target.getService(), target.getType(),
                name, from, from.plusSeconds(60), stats.stream().map(Enum::name).toList(), List.of());
        Map<Stat, Double> result = new LinkedHashMap<>();
        for (Stat stat : stats) {
            result.put(stat, raw.get(stat.name()));
        }
        return result;
    }
}
