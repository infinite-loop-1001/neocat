package com.neocat.catalog.domain.report;

import java.time.Instant;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.ToString;
import org.springframework.modulith.NamedInterface;

/**
 * 查询时间范围。
 *
 * @param from 起点（含）
 * @param to   终点（不含）
 */
@NamedInterface("catalog")
@Getter
@EqualsAndHashCode
@ToString
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
