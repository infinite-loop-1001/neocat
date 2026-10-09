package com.neocat.common.time.range;

import java.time.LocalDate;

import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.ToString;
import org.springframework.modulith.NamedInterface;

/**
 * 某个自然日（平台时区）。
 */
@NamedInterface("time")
@Getter
@EqualsAndHashCode
@ToString
public final class Day implements RangeSpec {
    private final LocalDate date;

    public Day(LocalDate date) {
        this.date = date;
    }

}
