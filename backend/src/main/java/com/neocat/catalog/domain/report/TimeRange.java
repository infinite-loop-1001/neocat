package com.neocat.catalog.domain.report;

import java.time.Instant;

/**
 * 查询时间范围。
 *
 * @param from 起点（含）
 * @param to   终点（不含）
 */
@org.springframework.modulith.NamedInterface("catalog")
@lombok.Getter
@lombok.EqualsAndHashCode
@lombok.ToString
public class TimeRange {
    private final Instant from;

    private final Instant to;

    public TimeRange(Instant from, Instant to) {
        this.from = from;
        this.to = to;
    }

    public boolean contains(Instant instant) {
        return !instant.isBefore(from) && instant.isBefore(to);
    }
}
