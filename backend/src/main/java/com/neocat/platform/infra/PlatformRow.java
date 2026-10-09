package com.neocat.platform.infra;

import lombok.Data;

import java.sql.Timestamp;

/**
 * 平台档案行（列名与 nc_platform_profile 一致）。
 */
@Data
public class PlatformRow {
    private boolean initialized;

    private String timezone;

    private int slowUrlMs;

    private int slowSqlMs;

    private int slowCallMs;

    private int slowCacheMs;

    private Timestamp initializedAt;
}
