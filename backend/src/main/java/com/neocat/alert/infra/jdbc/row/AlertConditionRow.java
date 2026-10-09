package com.neocat.alert.infra.jdbc.row;

import java.math.BigDecimal;

import lombok.Data;

/**
 * 条件行。
 */
@Data
public class AlertConditionRow {

    private String stat;

    private String comparator;

    private BigDecimal threshold;
}
