package com.neocat.catalog.infra;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.time.Instant;
import java.util.List;

/**
 * 服务与实例目录 Mapper（表 {@code nc_service} / {@code nc_instance}）。
 *
 * <p>upsert 使用 MySQL 的 {@code ON DUPLICATE KEY UPDATE}：
 * 已存在时只更新 {@code last_seen_at}，保留 {@code first_seen_at}。
 */
@Mapper
public interface CatalogMapper {

    int upsertService(@Param("name") String name, @Param("at") Instant at);

    ServiceRow selectService(@Param("name") String name);

    List<ServiceRow> selectAllServices();

    void upsertInstance(@Param("serviceName") String serviceName,
                        @Param("instanceId") String instanceId,
                        @Param("at") Instant at);

    InstanceRow selectInstance(@Param("serviceName") String serviceName,
                                                       @Param("instanceId") String instanceId);

    List<InstanceRow> selectInstances(@Param("serviceName") String serviceName);
}
