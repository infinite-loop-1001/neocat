package com.neocat.query.domain.series;

import java.math.BigDecimal;

import java.util.Objects;

import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.ToString;
import org.springframework.modulith.NamedInterface;

/**
 * 序列上的一个数据点（技术方案 03 §4.10）。
 *
 * <p>关键约定：{@link #value} 为 {@code null} 表示**缺口**，绝不写 0。
 * 次数类在确认无调用时写 0；耗时/比例类在确认无调用时为 {@code null} 并标记 {@link Quality#ZERO}。
 *
 * @param bucketStart    桶起点（含）
 * @param bucketEnd      桶终点（不含）
 * @param value          值；null 表示缺口或无值
 * @param quality        质量标记
 * @param coveredSeconds 桶实际覆盖秒数
 */
@NamedInterface("query")
@Getter
@EqualsAndHashCode
@ToString
public class Point {
    private final long bucketStart;

    private final long bucketEnd;

    private final BigDecimal value;

    private final Quality quality;

    private final long coveredSeconds;

    public Point(long bucketStart, long bucketEnd, BigDecimal value, Quality quality, long coveredSeconds) {
        this.bucketStart = bucketStart;
        this.bucketEnd = bucketEnd;
        this.value = value;
        this.quality = quality;
        this.coveredSeconds = coveredSeconds;
    }

    public boolean realtime() {
        return Objects.equals(quality, Quality.REALTIME);
    }

    public boolean partial() {
        return Objects.equals(quality, Quality.PARTIAL);
    }

    /**
     * 是否可参与告警窗口比较：缺数点不可比较（PRD 06 §6）。
     */
    public boolean comparable() {
        return Objects.nonNull(value) && quality.comparable();
    }
}
