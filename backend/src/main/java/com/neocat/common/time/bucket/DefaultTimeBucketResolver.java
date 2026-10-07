package com.neocat.common.time.bucket;

import com.neocat.common.time.range.RangeQuick;
import com.neocat.common.time.range.RangeSpec;

import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;

/**
 * {@link TimeBucketResolver} 的默认实现。
 *
 * <p>实现依据 PRD 03 §2：
 * <ul>
 *   <li>§2.1 固定周期粒度：小时→1 分钟；日→10 分钟；周→1 小时；月→1 自然日。</li>
 *   <li>§2.2 快捷范围粒度：1h→1min，3h→5min，6h→10min，12h→20min，24h→1h，今天→10min，本周→1h。</li>
 *   <li>§2.3 所有点为左闭右开区间 {@code [bucketStart, bucketEnd)}。</li>
 *   <li>滚动范围首尾部分桶保留，按实际查询范围计算覆盖秒数并标记 partial。</li>
 * </ul>
 *
 * <p>桶边界一律按平台时区的固定边界对齐（天/周/月对齐自然边界；分钟粒度对齐自然分钟）。
 */
@Component
@org.springframework.modulith.NamedInterface("time")
public class DefaultTimeBucketResolver implements TimeBucketResolver {

    @Override
    public List<Bucket> resolve(RangeSpec spec, ZoneId zone) {
        // 注意：Java 17 不支持 switch 模式匹配（预览特性），此处使用 instanceof 模式链。
        if (spec instanceof RangeSpec.Hour hour) {
            Instant start = alignToHour(hour.getHourStart(), zone);
            return fixedRange(start, start.plus(Duration.ofHours(1)), Granularity.MINUTE_1, zone);
        }
        if (spec instanceof RangeSpec.Day day) {
            Instant start = day.getDate().atStartOfDay(zone).toInstant();
            Instant end = day.getDate().plusDays(1).atStartOfDay(zone).toInstant();
            return fixedRange(start, end, Granularity.MINUTE_10, zone);
        }
        if (spec instanceof RangeSpec.Week week) {
            LocalDate monday = week.getAnyDateInWeek().with(java.time.DayOfWeek.MONDAY);
            Instant start = monday.atStartOfDay(zone).toInstant();
            Instant end = monday.plusWeeks(1).atStartOfDay(zone).toInstant();
            return fixedRange(start, end, Granularity.HOUR_1, zone);
        }
        if (spec instanceof RangeSpec.Month month) {
            LocalDate first = month.getMonth().atDay(1);
            Instant start = first.atStartOfDay(zone).toInstant();
            Instant end = first.plusMonths(1).atStartOfDay(zone).toInstant();
            return fixedRange(start, end, Granularity.DAY_1, zone);
        }
        if (spec instanceof RangeSpec.QuickRange quick) {
            return quickRange(quick, zone);
        }
        if (spec instanceof RangeSpec.Explicit explicit) {
            return explicitRange(explicit, zone);
        }
        throw new IllegalArgumentException("不支持的 RangeSpec: " + spec);
    }
    @Override
    public Instant alignStart(Instant instant, Granularity granularity, ZoneId zone) {
        ZonedDateTime local = instant.atZone(zone);
        ZonedDateTime aligned = switch (granularity) {
            case MINUTE_1 -> local.truncatedTo(ChronoUnit.MINUTES);
            case MINUTE_5 -> local.withMinute(local.getMinute() / 5 * 5).withSecond(0).withNano(0);
            case MINUTE_10 -> local.withMinute(local.getMinute() / 10 * 10).withSecond(0).withNano(0);
            case MINUTE_20 -> local.withMinute(local.getMinute() / 20 * 20).withSecond(0).withNano(0);
            case HOUR_1 -> local.truncatedTo(ChronoUnit.HOURS);
            case DAY_1 -> local.toLocalDate().atStartOfDay(zone);
        };
        return aligned.toInstant();
    }

    // ── 内部实现 ─────────────────────────────────────────────

    /** 固定周期：桶边界与自然边界重合，全部为完整桶。 */
    private List<Bucket> fixedRange(Instant start, Instant end, Granularity granularity, ZoneId zone) {
        List<Bucket> buckets = new ArrayList<>();
        long stepSeconds = granularity.seconds();
        Instant cursor = start;
        while (cursor.isBefore(end)) {
            Instant bucketEnd = cursor.plusSeconds(stepSeconds);
            buckets.add(new Bucket(cursor, bucketEnd, false, stepSeconds));
            cursor = bucketEnd;
        }
        return buckets;
    }
    /** 快捷范围：从对齐后的起点滚动到 now，首尾桶标记部分覆盖。 */
    private List<Bucket> quickRange(RangeSpec.QuickRange quick, ZoneId zone) {
        Instant now = quick.getNow();
        Granularity granularity = quick.getQuick().granularity();
        long stepSeconds = granularity.seconds();
        Instant alignedNowEnd = alignStart(now, granularity, zone).plusSeconds(stepSeconds);

        Instant rawStart = quickStart(quick.getQuick(), now, zone);
        Instant rangeStart = alignStart(rawStart, granularity, zone);

        List<Bucket> buckets = new ArrayList<>();
        Instant cursor = rangeStart;
        while (cursor.isBefore(alignedNowEnd)) {
            Instant bucketEnd = cursor.plusSeconds(stepSeconds);
            Instant effectiveFrom = cursor.isBefore(rawStart) ? rawStart : cursor;
            Instant effectiveTo = bucketEnd.isAfter(now) ? now : bucketEnd;
            long coveredSeconds = Math.max(0, Duration.between(effectiveFrom, effectiveTo).getSeconds());
            boolean partial = coveredSeconds < stepSeconds;
            buckets.add(new Bucket(cursor, bucketEnd, partial, partial ? coveredSeconds : stepSeconds));
            cursor = bucketEnd;
        }
        return buckets;
    }
    private Instant quickStart(RangeQuick quick, Instant now, ZoneId zone) {
        return switch (quick) {
            case RECENT_1H -> now.minus(Duration.ofHours(1));
            case RECENT_3H -> now.minus(Duration.ofHours(3));
            case RECENT_6H -> now.minus(Duration.ofHours(6));
            case RECENT_12H -> now.minus(Duration.ofHours(12));
            case RECENT_24H -> now.minus(Duration.ofHours(24));
            case TODAY -> now.atZone(zone).toLocalDate().atStartOfDay(zone).toInstant();
            case THIS_WEEK -> now.atZone(zone).toLocalDate()
                    .with(java.time.DayOfWeek.MONDAY).atStartOfDay(zone).toInstant();
        };
    }
    /** 显式范围：粒度由调用方给定；首尾桶按 from/to 计算覆盖秒数。 */
    private List<Bucket> explicitRange(RangeSpec.Explicit explicit, ZoneId zone) {
        Instant from = explicit.getFrom();
        Instant to = explicit.getTo();
        long stepSeconds = explicit.getGranularity().seconds();
        Instant alignedFrom = alignStart(from, explicit.getGranularity(), zone);

        List<Bucket> buckets = new ArrayList<>();
        Instant cursor = alignedFrom;
        while (cursor.isBefore(to)) {
            Instant bucketEnd = cursor.plusSeconds(stepSeconds);
            Instant effectiveFrom = cursor.isBefore(from) ? from : cursor;
            Instant effectiveTo = bucketEnd.isAfter(to) ? to : bucketEnd;
            long coveredSeconds = Math.max(0, Duration.between(effectiveFrom, effectiveTo).getSeconds());
            boolean partial = coveredSeconds < stepSeconds;
            buckets.add(new Bucket(cursor, bucketEnd, partial, partial ? coveredSeconds : stepSeconds));
            cursor = bucketEnd;
        }
        return buckets;
    }
    private Instant alignToHour(Instant instant, ZoneId zone) {
        return instant.atZone(zone).truncatedTo(ChronoUnit.HOURS).toInstant();
    }
}
