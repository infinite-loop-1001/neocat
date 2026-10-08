package com.neocat.catalog.domain.service;

import com.neocat.catalog.domain.entry.InstanceEntry;
import com.neocat.catalog.domain.entry.ServiceEntry;

import java.time.Instant;
import java.util.List;
import org.springframework.modulith.NamedInterface;

@NamedInterface("catalog")
public interface CatalogRepository {

    /** 幂等 upsert 服务；返回当前记录。 */
    ServiceEntry upsertService(String serviceName, Instant at);

    /** 幂等 upsert 实例。 */
    InstanceEntry upsertInstance(String serviceName, String instanceId, Instant at);

    List<ServiceEntry> services();

    List<InstanceEntry> instancesOf(String serviceName);
}
