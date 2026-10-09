package com.neocat.query.domain.stat.result;

import java.math.BigDecimal;

import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.ToString;
import org.springframework.modulith.NamedInterface;

/**
 * 标准分位集合。
 */
@NamedInterface("query")
@Getter
@EqualsAndHashCode
@ToString
public class Percentiles {
    private final BigDecimal tp50;

    private final BigDecimal tp90;

    private final BigDecimal tp95;

    private final BigDecimal tp99;

    private final BigDecimal tp999;

    private final BigDecimal tp9999;

    public Percentiles(BigDecimal tp50,
                       BigDecimal tp90, BigDecimal tp95,
                       BigDecimal tp99, BigDecimal tp999, BigDecimal tp9999) {
        this.tp50 = tp50;
        this.tp90 = tp90;
        this.tp95 = tp95;
        this.tp99 = tp99;
        this.tp999 = tp999;
        this.tp9999 = tp9999;
    }

}
