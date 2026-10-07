package com.neocat.dashboard.infra.service;

import com.neocat.dashboard.domain.card.Card;
import com.neocat.dashboard.domain.access.CardInputSource;
import com.neocat.dashboard.domain.formula.FormulaParser;
import com.neocat.query.api.internal.ReportPoints;
import com.neocat.query.domain.stat.Stat;
import org.springframework.stereotype.Component;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.apache.commons.collections4.ListUtils;

@Component
public class ReportCardInputSource implements CardInputSource {
    private final ReportPoints reports;

    private final FormulaParser parser;

    public ReportCardInputSource(ReportPoints reports) {
        this.reports = reports;
        this.parser = new FormulaParser();
    }
    @Override
    public Map<Long, Map<Stat, Double>> buckets(Card card, Instant from, Instant to, long bucketSeconds) {
        var parsed = parser.parse(card.getFormula());
        if (!parsed.valid()) {
            throw new IllegalArgumentException("Invalid saved card formula");
        }
        List<Stat> stats = parsed.getFormula().referencedStats().stream().distinct().toList();
        Map<Long, Map<Stat, Double>> result = new LinkedHashMap<>();
        for (long[] boundary : bucketBoundaries(from, to, bucketSeconds)) {
            String name = Objects.equals("METRIC", card.getTargetKind()) ? card.getMetricLabels() : card.getTargetName();
            Map<String, Double> raw = reports.values(card.getTargetKind(), card.getService(), card.getTargetType(),
                    name, Instant.ofEpochMilli(boundary[0]), Instant.ofEpochMilli(boundary[1]),
                    stats.stream().map(Enum::name).toList(),
                    ListUtils.emptyIfNull(card.getInstanceScope()));
            Map<Stat, Double> values = new LinkedHashMap<>();
            for (Stat stat : stats) {
                values.put(stat, raw.get(stat.name()));
            }
            result.put(boundary[0], values);
        }
        return result;
    }
    @Override
    public List<long[]> bucketBoundaries(Instant from, Instant to, long bucketSeconds) {
        if (bucketSeconds <= 0) {
            throw new IllegalArgumentException("Bucket size must be positive");
        }
        List<long[]> result = new ArrayList<>();
        long end = to.toEpochMilli();
        long size = Math.multiplyExact(bucketSeconds, 1000L);
        for (long start = from.toEpochMilli(); start < end; start = Math.addExact(start, size)) {
            result.add(new long[]{start, Math.min(Math.addExact(start, size), end)});
        }
        return result;
    }
}
