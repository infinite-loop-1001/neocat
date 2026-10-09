package com.neocat.dashboard.infra.jdbc;

import java.math.BigDecimal;

import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.ToString;

@Getter
@EqualsAndHashCode
@ToString
public class ThresholdLineRow {
    private final String direction;

    private final BigDecimal value;

    public ThresholdLineRow(String direction, BigDecimal value) {
        this.direction = direction;
        this.value = value;
    }
}
