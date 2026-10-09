package com.neocat.catalog.infra.row;

import lombok.Data;

import java.time.Instant;

/**
 * 实例行（列名与 nc_instance 一致）。
 */
@Data
public class InstanceRow {
    private String serviceName;

    private String instanceId;

    private Instant firstSeenAt;

    private Instant lastSeenAt;
}
