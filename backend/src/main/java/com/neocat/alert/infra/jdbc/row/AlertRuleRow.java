package com.neocat.alert.infra.jdbc.row;

import lombok.Data;

import java.sql.Timestamp;

/**
 * 规则行。
 */
@Data
public class AlertRuleRow {

    private Long id;

    private String scope;

    private Long orgId;

    private String name;

    private String description;

    private String targetKind;

    private String reportKind;

    private String targetService;

    private String targetType;

    private String targetName;

    private String targetMetricLabels;

    private String formulaStats;

    private String targetStat;

    private String channels;

    private Long targetCardId;

    private String combinator;

    private int windowPoints;

    private boolean enabled;

    private boolean invalid;

    private Timestamp stateSince;
}
