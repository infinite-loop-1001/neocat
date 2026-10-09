package com.neocat.analysis.domain.schedule;

import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.ToString;
import org.springframework.modulith.NamedInterface;

/**
 * 清理结果。
 */
@NamedInterface("analysis")
@Getter
@EqualsAndHashCode
@ToString
public class EvictionResult {
    private final long minuteBuckets;

    private final long hourBuckets;

    private final long longTermBuckets;

    public EvictionResult(long minuteBuckets, long hourBuckets, long longTermBuckets) {
        this.minuteBuckets = minuteBuckets;
        this.hourBuckets = hourBuckets;
        this.longTermBuckets = longTermBuckets;
    }

}
