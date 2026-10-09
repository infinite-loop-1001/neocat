package com.neocat.catalog.infra.row;

import lombok.Data;

import java.time.Instant;

/**
 * 服务行（列名与 nc_service 一致）。
 */
@Data
public class ServiceRow {

    private String name;

    private Instant firstSeenAt;

    private Instant lastSeenAt;

}
