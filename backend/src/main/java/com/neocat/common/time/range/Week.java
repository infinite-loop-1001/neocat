package com.neocat.common.time.range;

import java.time.LocalDate;

import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.ToString;
import org.springframework.modulith.NamedInterface;

/**
 * 某个自然周（平台时区周一 00:00 起）。
 */
@NamedInterface("time")
@Getter
@EqualsAndHashCode
@ToString
public final class Week implements RangeSpec {
    private final LocalDate anyDateInWeek;

    public Week(LocalDate anyDateInWeek) {
        this.anyDateInWeek = anyDateInWeek;
    }

}
