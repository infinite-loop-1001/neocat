package com.neocat.query.infra.service;

import com.neocat.catalog.domain.report.ReportKind;
import com.neocat.catalog.domain.report.SeriesPresence;
import com.neocat.catalog.domain.report.TimeRange;
import com.neocat.query.api.internal.SeriesDataLookup;
import org.springframework.stereotype.Component;

/** The report-reading side implements the catalog-owned port to preserve module direction. */
@Component
public class ReportSeriesPresenceAdapter implements SeriesPresence {
    private final SeriesDataLookup lookup;

    public ReportSeriesPresenceAdapter(SeriesDataLookup lookup) {
        this.lookup = lookup;
    }
    @Override
    public boolean hasData(String serviceName, ReportKind kind, TimeRange range) {
        return lookup.hasData(serviceName, kind.name(), null, range.getFrom(), range.getTo());
    }
    @Override
    public boolean hasInstanceData(String serviceName, String instanceId, ReportKind kind, TimeRange range) {
        return lookup.hasData(serviceName, kind.name(), instanceId, range.getFrom(), range.getTo());
    }
}
