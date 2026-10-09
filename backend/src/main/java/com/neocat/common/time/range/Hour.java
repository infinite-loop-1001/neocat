package com.neocat.common.time.range;

import java.time.Instant;

import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.ToString;
import org.springframework.modulith.NamedInterface;

/**
 * 某个整点小时。
 */
@NamedInterface("time")
@Getter
@EqualsAndHashCode
@ToString
public final class Hour implements RangeSpec {

    private final Instant hourStart;

    public Hour(Instant hourStart) {
        this.hourStart = hourStart;
    }
}