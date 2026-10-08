package com.neocat.catalog.domain.service;

import com.neocat.catalog.domain.entry.InstanceEntry;
import com.neocat.catalog.domain.entry.ServiceEntry;
import com.neocat.catalog.domain.report.ReportKind;
import com.neocat.catalog.domain.report.SeriesPresence;
import com.neocat.catalog.domain.report.TimeRange;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.modulith.NamedInterface;
import org.springframework.stereotype.Service;

/**
 * 服务与实例自动发现（PRD 02 §5）。
 *
 * <p>关键顺序不变式：调用方必须在尝试入队**之前**调用本服务，
 * 这样即使随后队列已满导致整棵树被丢弃，服务与实例仍然出现在目录中
 * （PRD 02 §5：「即使之后队列已满导致整棵树丢弃，服务和实例仍然可以在目录中被发现」）。
 *
 * <p>目录本身不做「永久空壳展示」：是否展示由
 * {@link #servicesWithData(ReportKind, TimeRange)} 按当前报表类型与时间范围动态过滤。
 */
@NamedInterface("catalog")
@Service
public class CatalogService {

    private final CatalogRepository repository;

    private final SeriesPresence seriesPresence;

    public CatalogService(CatalogRepository repository) {
        this(repository, SeriesPresence.denyAll());
    }
    @Autowired
    public CatalogService(CatalogRepository repository, SeriesPresence seriesPresence) {
        this.repository = repository;
        this.seriesPresence = seriesPresence;
    }
    /** 合法身份校验通过后立即执行；幂等。 */
    public ServiceEntry ensureService(String serviceName, Instant at) {
        if (Objects.isNull(serviceName) || serviceName.isBlank()) {
            throw new IllegalArgumentException("serviceName 不能为空");
        }
        return repository.upsertService(serviceName, at);
    }
    /** 实例在服务下唯一；幂等。 */
    public InstanceEntry ensureInstance(String serviceName, String instanceId, Instant at) {
        if (Objects.isNull(serviceName) || serviceName.isBlank()) {
            throw new IllegalArgumentException("serviceName 不能为空");
        }
        if (Objects.isNull(instanceId) || instanceId.isBlank()) {
            throw new IllegalArgumentException("instanceId 不能为空");
        }
        return repository.upsertInstance(serviceName, instanceId, at);
    }
    /** 一次调用同时发现服务与实例（上报接收路径使用）。 */
    public void discover(String serviceName, String instanceId, Instant at) {
        ensureService(serviceName, at);
        ensureInstance(serviceName, instanceId, at);
    }
    public List<ServiceEntry> allServices() {
        return repository.services();
    }
    public List<InstanceEntry> instances(String serviceName) {
        return repository.instancesOf(serviceName);
    }
    public boolean exists(String serviceName) {
        return repository.services().stream().anyMatch(s -> Objects.equals(s.getName(), serviceName));
    }

    // ── 动态过滤（PRD 02 §5、PRD 00 §4.1）────────────────────

    /**
     * 按「当前报表类型 + 当前时间范围」过滤出有数据的服务。
     *
     * <p>规则：
     * <ul>
     *   <li>无该类型数据的服务不展示；</li>
     *   <li>历史时间范围可以重新显示历史上有数据的服务；</li>
     *   <li>不做永久空壳展示、手工删除或归档状态。</li>
     * </ul>
     */
    public List<String> servicesWithData(ReportKind kind, TimeRange range) {
        return repository.services().stream()
                .map(ServiceEntry::getName)
                .filter(name -> seriesPresence.hasData(name, kind, range))
                .sorted()
                .toList();
    }
    public List<String> instancesWithData(String serviceName, ReportKind kind, TimeRange range) {
        return repository.instancesOf(serviceName).stream()
                .map(InstanceEntry::getInstanceId)
                .filter(instanceId -> seriesPresence.hasInstanceData(serviceName, instanceId, kind, range))
                .sorted()
                .toList();
    }
}
