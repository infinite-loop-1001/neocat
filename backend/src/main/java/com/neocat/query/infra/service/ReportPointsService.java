package com.neocat.query.infra.service;

import java.math.BigDecimal;

import com.neocat.query.infra.port.ReportDataPort;

import com.neocat.query.api.internal.ReportPoints;
import com.neocat.query.domain.stat.Stat;
import com.neocat.query.domain.stat.StatCalculator;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.neocat.common.time.bucket.Granularity;

@Component
public class ReportPointsService implements ReportPoints {
    private final ReportDataPort reports;

    private final StatCalculator calculator;

    public ReportPointsService(ReportDataPort reports, StatCalculator calculator) {
        this.reports = reports;
        this.calculator = calculator;
    }

    @Override
    public Map<String, BigDecimal> values(String kind, String service, String type, String name,
                                          Instant from, Instant to, List<String> stats, List<String> instances) {
        // 告警与大盘的输入源都是「单个分钟点」（见 ReportMinutePointSource / CardInputSource），
        // 因此这里固定按 1 分钟粒度取数，与调用方传入的 [from, to) 对齐。
        var rows = reports.rows(kind, service, type, name, from, to,
                Granularity.MINUTE_1, instances);
        Map<String, BigDecimal> result = new LinkedHashMap<>();
        for (String stat : stats) {
            Stat parsed = Stat.parse(stat);
            long coverage = rows.stream().mapToLong(r -> r.coveredSeconds()).max().orElse(0L);
            result.put(stat, calculator.computeIntermediate(rows, parsed, coverage));
        }
        return result;
    }
}
