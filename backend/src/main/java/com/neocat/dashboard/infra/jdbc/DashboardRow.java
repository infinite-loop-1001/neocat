package com.neocat.dashboard.infra.jdbc;

import lombok.Data;

/**
 * 大盘行（列名与 nc_dashboard 一致）。
 */
@Data
public class DashboardRow {
    private Long id;

    private long orgId;

    private String name;

    private int orderNo;
}
