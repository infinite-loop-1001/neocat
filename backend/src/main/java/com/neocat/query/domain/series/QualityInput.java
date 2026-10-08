package com.neocat.query.domain.series;

import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.ToString;
import org.springframework.modulith.NamedInterface;

/**
 * 数据质量判定输入（PRD 00 §6、PRD 02 §7–8）。
 *
 * @param seriesExists    该序列在桶内是否有记录（存在即代表被观测过）
 * @param count           桶内总次数
 * @param dropped         该桶是否存在队列满丢弃记录
 * @param mergedIntoOther Metric 具体序列该小时是否被并入 other
 * @param partial         是否为部分覆盖桶
 * @param realtime        是否为当前仍在写入的桶
 * @param coveredSeconds  桶实际覆盖秒数
 */
@NamedInterface("query")
@Getter
@EqualsAndHashCode
@ToString
public class QualityInput {
    private final boolean seriesExists;

    private final long count;

    private final boolean dropped;

    private final boolean mergedIntoOther;

    private final boolean partial;

    private final boolean realtime;

    private final long coveredSeconds;

    public QualityInput(boolean seriesExists, long count, boolean dropped, boolean mergedIntoOther, boolean partial, boolean realtime, long coveredSeconds) {
        this.seriesExists = seriesExists;
        this.count = count;
        this.dropped = dropped;
        this.mergedIntoOther = mergedIntoOther;
        this.partial = partial;
        this.realtime = realtime;
        this.coveredSeconds = coveredSeconds;
    }

    public static QualityInput missing() {
        return new QualityInput(false, 0, false, false, false, false, 0);
    }
    public static QualityInput present(long count) {
        return new QualityInput(true, count, false, false, false, false, 60);
    }
}