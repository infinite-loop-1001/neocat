package com.neocat.query.infra.service;

import com.neocat.query.infra.port.ReportDataPort;
import com.neocat.query.api.internal.SeriesDataLookup;
import org.springframework.stereotype.Component;
import java.time.Instant;
import java.util.Objects;
import org.apache.commons.collections4.CollectionUtils;

@Component
public class SeriesDataLookupService implements SeriesDataLookup {
    private final ReportDataPort reports;

    public SeriesDataLookupService(ReportDataPort reports) {
        this.reports = reports;
    }
    @Override
    public boolean hasData(String service, String kind, String instance, Instant from, Instant to) {
        if (Objects.nonNull(instance)) {
            return reports.instancesWithData(kind, service, from, to).contains(instance);
        }
        return CollectionUtils.isNotEmpty(reports.typesOf(kind, service, from, to));
    }
}
