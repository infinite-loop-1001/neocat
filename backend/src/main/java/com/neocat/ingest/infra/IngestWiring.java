package com.neocat.ingest.infra;

import com.neocat.catalog.api.internal.CatalogDiscovery;
import com.neocat.ingest.domain.receive.CatalogGateway;
import com.neocat.ingest.domain.validation.FingerprintCalculator;
import com.neocat.ingest.domain.idempotency.HistoricalFingerprintLookup;
import com.neocat.ingest.domain.idempotency.IdempotencyService;
import com.neocat.ingest.domain.idempotency.IdempotencyStore;
import com.neocat.ingest.domain.receive.IngestService;
import com.neocat.ingest.domain.validation.LatenessPolicy;
import com.neocat.ingest.domain.tree.MessageTree;
import com.neocat.ingest.domain.receive.QualityEventSink;
import com.neocat.ingest.domain.validation.TreeValidator;

import org.springframework.context.annotation.Bean;
import java.util.function.Supplier;

@org.springframework.context.annotation.Configuration
@org.springframework.context.annotation.DependsOn("ingestConfig")
public class IngestWiring {
    /** 接收链路里的身份发现：与目录服务共用同一仓储，保证「发现先于入队」。 */
    @Bean
    public CatalogGateway catalogGateway(CatalogDiscovery catalog) {
        return catalog::discover;
    }
    // ── 上报接收 ─────────────────────────────────────────────

}
