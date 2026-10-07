package com.neocat.catalog.infra;

import com.neocat.catalog.api.internal.CatalogDiscovery;
import com.neocat.catalog.api.internal.ServicePresence;
import com.neocat.catalog.domain.service.CatalogService;
import com.neocat.catalog.domain.report.ReportKind;
import com.neocat.catalog.domain.report.SeriesPresence;
import com.neocat.catalog.domain.report.TimeRange;
import org.springframework.stereotype.Component;

import java.time.Instant;

@Component
public class CatalogInternalService implements CatalogDiscovery, ServicePresence {
    private final CatalogService catalog;

    private final SeriesPresence presence;

    public CatalogInternalService(CatalogService catalog, SeriesPresence presence) {
        this.catalog = catalog;
        this.presence = presence;
    }
    @Override
    public void discover(String serviceName, String instanceId, Instant at) {
        catalog.discover(serviceName, instanceId, at);
    }
    @Override
    public boolean hasRecentTransactions(String serviceName, Instant from, Instant to) {
        return presence.hasData(serviceName, ReportKind.TRANSACTION, new TimeRange(from, to));
    }
}
