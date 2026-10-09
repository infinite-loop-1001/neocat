package com.neocat.dashboard.infra.jdbc.row;

import lombok.Data;

/**
 * 卡片行（列名与 nc_card 一致）。
 */
@Data
public class CardRow {
    private Long id;

    private long dashboardId;

    private String service;

    private String targetKind;

    private String targetType;

    private String targetName;

    private String metricName;

    private String metricLabels;

    private String instanceScope;

    private String formula;

    private String formulaUnit;

    private String timeRange;

    private int orderNo;
}
