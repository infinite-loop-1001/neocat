package com.neocat.common.time.range;

import java.time.YearMonth;

import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.ToString;
import org.springframework.modulith.NamedInterface;

/**
 * 某个自然月（平台时区月初 00:00 起）。
 */
@NamedInterface("time")
@Getter
@EqualsAndHashCode
@ToString
public final class Month implements RangeSpec {
    private final YearMonth month;

    public Month(YearMonth month) {
        this.month = month;
    }

}
