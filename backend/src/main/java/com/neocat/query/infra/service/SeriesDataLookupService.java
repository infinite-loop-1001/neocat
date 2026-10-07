package com.neocat.query.infra.service;

import com.neocat.query.infra.port.ReportDataPort;

import com.neocat.query.api.internal.SeriesDataLookup;
import org.springframework.stereotype.Component;

import java.time.Instant;

@Component
public class SeriesDataLookupService implements SeriesDataLookup {
    private final ReportDataPort reports;

    public SeriesDataLookupService(ReportDataPort reports) {
        this.reports = reports;
    }
    @Override
    public boolean hasData(String service, String kind, String instance, Instant from, Instant to) {
        if (instance != null) {
            return reports.instancesWithData(kind, service, from, to).contains(instance);
        }
        return !reports.typesOf(kind, service, from, to).isEmpty();
    }
}
