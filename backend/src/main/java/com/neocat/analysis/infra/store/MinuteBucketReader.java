package com.neocat.analysis.infra.store;

import com.neocat.analysis.domain.bucket.AggregatedRow;
import com.neocat.analysis.domain.bucket.AggregationLevel;
import com.neocat.analysis.domain.bucket.AggregationRoller;
import com.neocat.analysis.domain.bucket.HourlyReportStore;
import com.neocat.analysis.domain.bucket.MinuteBucket;
import com.neocat.analysis.domain.bucket.SeriesKey;
import com.neocat.analysis.domain.bucket.SeriesKind;

import java.time.Instant;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

/**
 * 当前小时报表的落库读取（技术方案 01-architecture.md §6.5、06 §9）。
 *
 * <p>把内存中的分钟桶转换为可持久化的聚合行。**只处理已完成的分钟点**：
 * 当前正在写入的分钟桶必须留到下一个分钟周期，否则会被后续数据覆盖成不完整值。
 *
 * <p>同时提供「清空已定稿小时」的能力，让内存占用不随时长增长。
 */
@org.springframework.stereotype.Component
public class MinuteBucketReader implements com.neocat.analysis.domain.bucket.MinuteBucketSource {

    private final HourlyReportStore store;

    private final Supplier<ZoneId> zone;

    public MinuteBucketReader(HourlyReportStore store, Supplier<ZoneId> zone) {
        this.store = store;
        this.zone = zone;
    }
    /**
     * 读取某分钟内全部序列的桶行。
     *
     * @param minuteStart 分钟起点（应已按平台时区对齐）
     * @return 该分钟所有序列的聚合行；无数据时为空列表
     */
    @Override
    public List<AggregatedRow> readMinute(Instant minuteStart) {
        Instant minute = minuteStart.truncatedTo(ChronoUnit.MINUTES);
        List<AggregatedRow> rows = new ArrayList<>();

        for (SeriesKey key : store.seriesKeys()) {
            MinuteBucket bucket = store.bucket(key, minute);
            if (bucket == null || (bucket.count() == 0 && bucket.valueCount() == 0)) {
                // 无数据不落库：避免用 count=0 的行伪装「确认无调用」
                continue;
            }
            AggregatedRow row = new AggregatedRow(key, minute, AggregationLevel.MINUTE, 60);
            row.addCount(bucket.count(), bucket.failCount(), bucket.durationSum(),
                    bucket.durationMin(), bucket.durationMax());
            synchronized (bucket) {
                row.addValue(bucket.valueSum(), bucket.valueCount());
                row.mergeLastValue(bucket.valueLast(), bucket.valueLastTime());
            }
            row.setDistribution(bucket.distribution().copy());
            rows.add(row);
        }
        return rows;
    }
    /**
     * 读取一个完整小时内的全部分钟桶行。
     *
     * @param hourStart 小时起点（应已按平台时区对齐）
     */
    @Override
    public List<AggregatedRow> readHour(Instant hourStart) {
        Instant start = hourStart.truncatedTo(ChronoUnit.HOURS);
        List<AggregatedRow> rows = new ArrayList<>();
        for (int i = 0; i < 60; i++) {
            rows.addAll(readMinute(start.plusSeconds(i * 60L)));
        }
        return rows;
    }
    /** 清空已定稿小时的分钟桶，释放内存。 */
    @Override
    public void clearHour(Instant hourStart) {
        store.clearHour(hourStart.truncatedTo(ChronoUnit.HOURS));
    }
    /**
     * 把一批分钟桶行聚合到目标层级。
     *
     * <p>直接复用 {@link AggregationRoller}，因此聚合不变式
     * （合并分子与分布后再算 avg / 分位）只有一处实现。
     */
    @Override
    public List<AggregatedRow> aggregate(List<AggregatedRow> minuteRows, AggregationLevel level) {
        return new AggregationRoller().roll(minuteRows, level, zone.get());
    }
    /** 当前小时内的全部序列类型统计（观测用）。 */
    public java.util.Map<String, Long> seriesCountByService() {
        return store.seriesCountByService();
    }
    /** 判断某序列是否为 Metric（落库时用于区分序列身份）。 */
    public static boolean isMetric(SeriesKey key) {
        return key.getKind() == SeriesKind.METRIC;
    }
}
