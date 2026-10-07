package com.neocat.catalog.api.internal;

import java.time.Instant;

/** 上报侧所需的目录发现契约，不泄漏目录内部仓储模型。 */
public interface CatalogDiscovery {
    void discover(String serviceName, String instanceId, Instant at);
}
