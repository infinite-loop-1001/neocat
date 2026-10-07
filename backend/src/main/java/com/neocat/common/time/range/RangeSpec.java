package com.neocat.common.time.range;

import com.neocat.common.time.bucket.Granularity;

import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;

/**
 * 查询时间范围描述。
 * 固定周期（HOUR / DAY / WEEK / MONTH）、快捷范围、或显式 from/to。
 */
@org.springframework.modulith.NamedInterface("time")
public sealed interface RangeSpec {

    /** 某个整点小时。 */
    @org.springframework.modulith.NamedInterface("time")
    @lombok.Getter
    @lombok.EqualsAndHashCode
    @lombok.ToString
    public static final class Hour implements RangeSpec {
        private final Instant hourStart;

        public Hour(Instant hourStart) {
            this.hourStart = hourStart;
        }

    }
    /** 某个自然日（平台时区）。 */
    @org.springframework.modulith.NamedInterface("time")
    @lombok.Getter
    @lombok.EqualsAndHashCode
    @lombok.ToString
    public static final class Day implements RangeSpec {
        private final LocalDate date;

        public Day(LocalDate date) {
            this.date = date;
        }

    }
    /** 某个自然周（平台时区周一 00:00 起）。 */
    @org.springframework.modulith.NamedInterface("time")
    @lombok.Getter
    @lombok.EqualsAndHashCode
    @lombok.ToString
    public static final class Week implements RangeSpec {
        private final LocalDate anyDateInWeek;

        public Week(LocalDate anyDateInWeek) {
            this.anyDateInWeek = anyDateInWeek;
        }

    }
    /** 某个自然月（平台时区月初 00:00 起）。 */
    @org.springframework.modulith.NamedInterface("time")
    @lombok.Getter
    @lombok.EqualsAndHashCode
    @lombok.ToString
    public static final class Month implements RangeSpec {
        private final YearMonth month;

        public Month(YearMonth month) {
            this.month = month;
        }

    }
    /** 快捷范围。 */
    @org.springframework.modulith.NamedInterface("time")
    @lombok.Getter
    @lombok.EqualsAndHashCode
    @lombok.ToString
    public static final class QuickRange implements RangeSpec {
        private final RangeQuick quick;

        private final Instant now;

        public QuickRange(RangeQuick quick, Instant now) {
            this.quick = quick;
            this.now = now;
        }

    }
    /** 显式范围；粒度可指定，缺省由范围长度推导。 */
    @org.springframework.modulith.NamedInterface("time")
    @lombok.Getter
    @lombok.EqualsAndHashCode
    @lombok.ToString
    public static final class Explicit implements RangeSpec {
        private final Instant from;

        private final Instant to;

        private final Granularity granularity;

        public Explicit(Instant from, Instant to, Granularity granularity) {
            this.from = from;
            this.to = to;
            this.granularity = granularity;
        }

    }
}

