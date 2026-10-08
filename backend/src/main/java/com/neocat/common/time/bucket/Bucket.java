package com.neocat.common.time.bucket;

import java.time.Instant;
import java.time.Duration;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.ToString;
import org.springframework.modulith.NamedInterface;

/**
 * 一个左闭右开的时间桶：[start, end)。
 * PRD 03 §2.3：横轴显示 start，Tooltip 显示完整区间。
 *
 * @param start        桶起点（含）
 * @param end          桶终点（不含）
 * @param partial      是否为部分覆盖桶（滚动范围首尾桶）
 * @param coveredSeconds 桶在查询范围内实际覆盖的秒数；完整桶等于桶长
 */
@NamedInterface("time")
@Getter
@EqualsAndHashCode
@ToString
public class Bucket {
    private final Instant start;

    private final Instant end;

    private final boolean partial;

    private final long coveredSeconds;

    public Bucket(Instant start, Instant end, boolean partial, long coveredSeconds) {
        this.start = start;
        this.end = end;
        this.partial = partial;
        this.coveredSeconds = coveredSeconds;
    }

    public boolean contains(Instant instant) {
        return !instant.isBefore(start) && instant.isBefore(end);
    }
    public long totalSeconds() {
        return Duration.between(start, end).getSeconds();
    }
}
