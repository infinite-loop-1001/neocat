package com.neocat.ingest.infra;

import com.neocat.catalog.api.internal.CatalogDiscovery;
import com.neocat.ingest.domain.receive.CatalogGateway;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.DependsOn;

@Configuration
@DependsOn("ingestConfig")
public class IngestWiring {
    /** 接收链路里的身份发现：与目录服务共用同一仓储，保证「发现先于入队」。 */
    @Bean
    public CatalogGateway catalogGateway(CatalogDiscovery catalog) {
        return catalog::discover;
    }
    // ── 上报接收 ─────────────────────────────────────────────

}
