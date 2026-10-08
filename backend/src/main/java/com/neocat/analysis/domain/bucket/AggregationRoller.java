package com.neocat.analysis.domain.bucket;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.time.DayOfWeek;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.ToString;
import org.springframework.modulith.NamedInterface;

/**
 * 分钟 → 小时 → 日 / 周 / 月 的滚动聚合（PRD 00 §10、PRD 03 §2.1，链路 23）。
 *
 * <p>核心不变式（PRD 03 §3、技术方案 06 §9）：
 * <ul>
 *   <li>聚合**先合并分子与 min/max，再由合并结果计算 avg / failureRate / 分位**；</li>
 *   <li>绝不平均子桶的平均值或分位 —— 测试用「10 次 100ms + 90 次 10ms」这类
 *       分布极不均的样例锁定正确值 19 与错误值 55 的区别；</li>
 *   <li>min 取真实最小、max 取真实最大，不参与求和。</li>
 * </ul>
 *
 * <p>桶起点一律按**平台时区**对齐：日 = 自然日 00:00，周 = 周一 00:00，月 = 月初 00:00。
 */
@NamedInterface("analysis")
public class AggregationRoller {

    /**
     * 把一组分钟（或小时）行合并为目标层级的行，按 UTC 对齐桶起点。
     *
     * <p>仅适用于分钟与小时层级（这两个层级的边界与 UTC 天然一致）。
     * 日 / 周 / 月必须使用 {@link #roll(List, AggregationLevel, ZoneId)} 传入平台时区。
     */
    public List<AggregatedRow> roll(List<AggregatedRow> rows, AggregationLevel level) {
        return roll(rows, level, ZoneId.of("UTC"));
    }
    /**
     * 把一组行合并为目标层级的行。
     *
     * @param rows  源行；同一序列的多个源桶会被合并
     * @param level 目标层级
     * @param zone  平台时区；日 / 周 / 月对齐依赖它
     * @return 每个 (序列, 目标桶) 一行，顺序与首次出现顺序一致
     */
    public List<AggregatedRow> roll(List<AggregatedRow> rows, AggregationLevel level, ZoneId zone) {
        @Getter
        @EqualsAndHashCode
        @ToString
        class RollKey {
            private final SeriesKey series;

            private final Instant start;

            public RollKey(SeriesKey series, Instant start) {
                this.series = series;
                this.start = start;
            }
 }
        Map<RollKey, AggregatedRow> merged = new LinkedHashMap<>();
        for (AggregatedRow row : rows) {
            Instant start = bucketStart(row.bucketStart(), level, zone);
            RollKey groupKey = new RollKey(row.key(), start);
            AggregatedRow target = merged.computeIfAbsent(groupKey,
                    k -> new AggregatedRow(row.key(), start, level, 0));
            target.addCount(row.count(), row.failCount(), row.durationSum(),
                    row.durationMin(), row.durationMax());
            target.addValue(row.valueSum(), row.valueCount());
            target.markValueCountMissing(row.valueCountMissing() || Objects.equals(row.key().getKind(), SeriesKind.METRIC) && row.valueCount() == 0);
            target.mergeLastValue(row.valueLast(), row.valueLastTime());
            // 分布必须一起合并：分位在查询期由合并后的分布重算（PRD 03 §3）。
            // 漏掉这一步，小时/日/周/月层级的 tp* 会全部变成无值，而且不会报错。
            target.setDistribution(target.distribution().merge(row.distribution()));
        }
        return new ArrayList<>(merged.values());
    }
    /**
     * 按层级把时刻对齐到桶起点（平台时区）。
     */
    public Instant bucketStart(Instant instant, AggregationLevel level, ZoneId zone) {
        ZonedDateTime local = instant.atZone(zone);
        ZonedDateTime aligned = switch (level) {
            case MINUTE -> local.truncatedTo(ChronoUnit.MINUTES);
            case HOUR -> local.truncatedTo(ChronoUnit.HOURS);
            case DAY -> local.toLocalDate().atStartOfDay(zone);
            case WEEK -> local.toLocalDate()
                    .with(DayOfWeek.MONDAY)
                    .atStartOfDay(zone);
            case MONTH -> local.toLocalDate()
                    .withDayOfMonth(1)
                    .atStartOfDay(zone);
        };
        return aligned.toInstant();
    }
    /**
     * 计算某桶的 coveredSeconds（PRD 03 §4 QPS 分母三态）。
     *
     * <p>规则：
     * <ol>
     *   <li>当前未结束的小时桶：整点至当前时刻的实际秒数；</li>
     *   <li>完整历史小时桶：3600；</li>
     *   <li>日 / 周 / 月 / 自定义：桶实际覆盖秒数（未结束的桶截到 now）。</li>
     * </ol>
     */
    public long coveredSeconds(Instant bucketStart, Instant bucketEnd, Instant now, AggregationLevel level) {
        Instant effectiveEnd = bucketEnd.isAfter(now) ? now : bucketEnd;
        if (!effectiveEnd.isAfter(bucketStart)) {
            return 0;
        }
        long covered = Duration.between(bucketStart, effectiveEnd).getSeconds();
        if (Objects.equals(level, AggregationLevel.HOUR) && !effectiveEnd.isBefore(bucketEnd)) {
            // 完整历史小时固定 3600，避免夏令时等导致的非 3600 秒差异
            return 3600L;
        }
        return covered;
    }
}
