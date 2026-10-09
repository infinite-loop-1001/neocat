package com.neocat.query.infra.datasource;

import com.neocat.common.time.clock.TimeProvider;

import com.neocat.query.infra.port.ReportDataPort;

import com.neocat.analysis.domain.bucket.AggregatedRow;
import com.neocat.common.time.bucket.Granularity;

import java.time.Instant;
import java.time.ZoneId;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.function.Supplier;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.ToString;

/** 已结束小时查 ClickHouse，当前小时查内存；跨界范围拆分后合并。 */
public  class ReportDataPortRouter implements ReportDataPort {
    private final ReportDataPort history;

    private final ReportDataPort current;

    private final Supplier<ZoneId> zone;

    public ReportDataPortRouter(ReportDataPort history, ReportDataPort current,
                                Supplier<ZoneId> zone) {
        this.history = history;
        this.current = current;
        this.zone = zone;
    }
    private Instant boundary() {
        return TimeProvider.now().atZone(zone.get()).truncatedTo(ChronoUnit.HOURS).toInstant();
    }
    @Getter
    @EqualsAndHashCode
    @ToString
    private static class Split {
        private final Instant historyEnd;

        private final Instant currentStart;

        private final boolean hasHistory;

        private final boolean hasCurrent;

        public Split(Instant historyEnd, Instant currentStart, boolean hasHistory, boolean hasCurrent) {
            this.historyEnd = historyEnd;
            this.currentStart = currentStart;
            this.hasHistory = hasHistory;
            this.hasCurrent = hasCurrent;
        }
 }

    private Split split(Instant from, Instant to) {
        Instant boundary = boundary();
        return new Split(to.isBefore(boundary) ? to : boundary,
                from.isAfter(boundary) ? from : boundary,
                from.isBefore(boundary) && to.isAfter(from),
                to.isAfter(boundary) && to.isAfter(from));
    }
    @Override
    public List<AggregatedRow> metricSourceRows(String service, String metric, Instant from, Instant to,
                                               Granularity granularity) {
        Split split = split(from, to);
        var result = new ArrayList<AggregatedRow>();
        if (split.isHasHistory()) result.addAll(history.metricSourceRows(service, metric, from, split.getHistoryEnd(), granularity));
        if (split.isHasCurrent()) result.addAll(current.metricSourceRows(service, metric, split.getCurrentStart(), to, granularity));
        return result;
    }
    @Override
    public List<AggregatedRow> rows(String kind, String service, String type, String name,
                                    Instant from, Instant to, Granularity granularity,
                                    List<String> instances) {
        Split split = split(from, to);
        var result = new ArrayList<AggregatedRow>();
        if (split.isHasHistory()) {
            result.addAll(history.rows(kind, service, type, name, from, split.getHistoryEnd(),
                    granularity, instances));
        }
        if (split.isHasCurrent()) {
            result.addAll(current.rows(kind, service, type, name, split.getCurrentStart(), to,
                    granularity, instances));
        }
        return List.copyOf(result);
    }
    @Override
    public List<String> instancesWithData(String kind, String service, Instant from, Instant to) {
        Split split = split(from, to);
        var result = new LinkedHashSet<String>();
        if (split.isHasHistory()) result.addAll(history.instancesWithData(kind, service, from, split.getHistoryEnd()));
        if (split.isHasCurrent()) result.addAll(current.instancesWithData(kind, service, split.getCurrentStart(), to));
        return List.copyOf(result);
    }
    @Override
    public List<String> namesOf(String kind, String service, String type, Instant from, Instant to) {
        Split split = split(from, to);
        var result = new LinkedHashSet<String>();
        if (split.isHasHistory()) result.addAll(history.namesOf(kind, service, type, from, split.getHistoryEnd()));
        if (split.isHasCurrent()) result.addAll(current.namesOf(kind, service, type, split.getCurrentStart(), to));
        return List.copyOf(result);
    }
    @Override
    public List<String> typesOf(String kind, String service, Instant from, Instant to) {
        Split split = split(from, to);
        var result = new LinkedHashSet<String>();
        if (split.isHasHistory()) result.addAll(history.typesOf(kind, service, from, split.getHistoryEnd()));
        if (split.isHasCurrent()) result.addAll(current.typesOf(kind, service, split.getCurrentStart(), to));
        return List.copyOf(result);
    }
    @Override
    public boolean droppedAt(String kind, String service, String type, String name, Instant bucketStart) {
        return bucketStart.isBefore(boundary()) && history.droppedAt(kind, service, type, name, bucketStart);
    }
    @Override
    public boolean droppedBetween(String kind, String service, String type, String name, Instant from, Instant to) {
        // Quality events are persisted on ingest, including those in the current hour.
        return history.droppedBetween(kind, service, type, name, from, to);
    }
    @Override
    public boolean mergedIntoOther(String service, String metricName, String labels, Instant hourStart) {
        return hourStart.isBefore(boundary()) ? history.mergedIntoOther(service, metricName, labels, hourStart)
                : current.mergedIntoOther(service, metricName, labels, hourStart);
    }
}