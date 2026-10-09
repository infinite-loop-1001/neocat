package com.neocat.query.domain.stat.result;

import com.neocat.analysis.domain.bucket.DurationDistribution;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.ToString;
import org.springframework.modulith.NamedInterface;

/**
 * 合并后的中间结果：分子、极值与已合并的分布。
 */
@NamedInterface("query")
@Getter
@EqualsAndHashCode
@ToString
public class Merged {
    private final long count;

    private final long failCount;

    private final long durationSum;

    private final long durationMin;

    private final long durationMax;

    private final DurationDistribution distribution;

    public Merged(long count, long failCount, long durationSum, long durationMin, long durationMax, DurationDistribution distribution) {
        this.count = count;
        this.failCount = failCount;
        this.durationSum = durationSum;
        this.durationMin = durationMin;
        this.durationMax = durationMax;
        this.distribution = distribution;
    }
}
