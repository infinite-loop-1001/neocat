package com.neocat.common.time.range;

import com.neocat.common.time.bucket.Granularity;

import java.time.Instant;

import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.ToString;
import org.springframework.modulith.NamedInterface;

/**
 * 显式范围；粒度可指定，缺省由范围长度推导。
 */
@NamedInterface("time")
@Getter
@EqualsAndHashCode
@ToString
public final class Explicit implements RangeSpec {
    private final Instant from;

    private final Instant to;

    private final Granularity granularity;

    public Explicit(Instant from, Instant to, Granularity granularity) {
        this.from = from;
        this.to = to;
        this.granularity = granularity;
    }

}
