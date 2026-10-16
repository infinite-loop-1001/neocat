package com.neocat.alert.infra.adapter;

import java.math.BigDecimal;

import com.neocat.alert.domain.entity.vo.source.MinutePoint;
import com.neocat.common.DecimalMath;

import com.google.common.collect.Lists;
import com.neocat.alert.domain.entity.vo.rule.AlertTarget;
import com.neocat.alert.domain.gateway.MinutePointSourceGateway;
import com.neocat.dashboard.api.internal.CardResults;
import com.neocat.query.api.internal.ReportPoints;
import com.neocat.query.domain.stat.Stat;
import org.apache.commons.collections4.CollectionUtils;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import org.springframework.beans.factory.annotation.Autowired;

@Component
public class ReportMinutePointSource implements MinutePointSourceGateway {
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
    public MinutePoint values(AlertTarget target, Long minute, List<Stat> stats) {
        return minutePoint(target, minute, stats);
    }

    @Override
    public List<MinutePoint> batchMinuteValues(AlertTarget target, List<Long> minutes, List<Stat> stats) {
        if (CollectionUtils.isEmpty(minutes) || CollectionUtils.isEmpty(stats)) {
            return Lists.newArrayList();
        }
        List<MinutePoint> result = new ArrayList<>(minutes.size());
        for (Long minute : minutes) {
            result.add(minutePoint(target, minute, stats));
        }
        return result;
    }

    /**
     * 每个完整分钟独立取数。区间接口会合并所有行，不能直接用于窗口内的多个分钟点。
     */
    private MinutePoint minutePoint(AlertTarget target, Long minute, List<Stat> stats) {
        Instant from = Instant.ofEpochMilli(minute);
        Instant to = from.plusSeconds(60);
        if (target.isCardResult()) {
            BigDecimal value = cards.value(target.getCardId(), from, to);
            Map<Stat, BigDecimal> values = new LinkedHashMap<>();
            for (Stat stat : stats) {
                values.put(stat, value);
            }
            return new MinutePoint(minute, values);
        }
        String name = Objects.equals("METRIC", target.getReportKind()) ? target.getMetricLabels() : target.getName();
        Map<String, BigDecimal> raw = reports.values(target.getReportKind(), target.getService(), target.getType(),
                name, from, to, stats.stream().map(Enum::name).toList(), Lists.newArrayList());
        Map<Stat, BigDecimal> values = new LinkedHashMap<>();
        for (Stat stat : stats) {
            values.put(stat, DecimalMath.result(raw.get(stat.name())));
        }
        return new MinutePoint(minute, values);
    }
}
