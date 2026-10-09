package com.neocat.catalog.infra;

import com.neocat.catalog.domain.service.CatalogRepository;
import com.neocat.catalog.domain.entry.InstanceEntry;
import com.neocat.catalog.domain.entry.ServiceEntry;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;

/**
 * 服务与实例目录的 MyBatis 适配器（表 {@code nc_service} / {@code nc_instance}）。
 *
 * <p>写入使用 upsert 语义（{@code ON DUPLICATE KEY UPDATE}），
 * 因此「首次合法上报即发现」是幂等的（PRD 02 §5）。
 */
@Repository
public class CatalogRepositoryAdapter implements CatalogRepository {

    private final CatalogMapper mapper;

    public CatalogRepositoryAdapter(CatalogMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    public ServiceEntry upsertService(String serviceName, Instant at) {
        mapper.upsertService(serviceName, at);
        ServiceRow row = mapper.selectService(serviceName);
        return new ServiceEntry(row.getName(), row.getFirstSeenAt(), row.getLastSeenAt());
    }

    @Override
    public InstanceEntry upsertInstance(String serviceName, String instanceId, Instant at) {
        mapper.upsertInstance(serviceName, instanceId, at);
        InstanceRow row = mapper.selectInstance(serviceName, instanceId);
        return new InstanceEntry(row.getServiceName(), row.getInstanceId(),
                row.getFirstSeenAt(), row.getLastSeenAt());
    }

    @Override
    public List<ServiceEntry> services() {
        return mapper.selectAllServices().stream()
                .map(r -> new ServiceEntry(r.getName(), r.getFirstSeenAt(), r.getLastSeenAt()))
                .toList();
    }

    @Override
    public List<InstanceEntry> instancesOf(String serviceName) {
        return mapper.selectInstances(serviceName).stream()
                .map(r -> new InstanceEntry(r.getServiceName(), r.getInstanceId(),
                        r.getFirstSeenAt(), r.getLastSeenAt()))
                .toList();
    }

}
