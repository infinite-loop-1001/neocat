package com.neocat.common.time.range;

import java.time.Instant;

import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.ToString;
import org.springframework.modulith.NamedInterface;

/**
 * 快捷范围。
 */
@NamedInterface("time")
@Getter
@EqualsAndHashCode
@ToString
public final class QuickRange implements RangeSpec {

    private final RangeQuick quick;

    private final Instant now;

    public QuickRange(RangeQuick quick, Instant now) {
        this.quick = quick;
        this.now = now;
    }

}
