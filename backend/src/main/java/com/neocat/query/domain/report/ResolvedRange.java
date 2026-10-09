package com.neocat.query.domain.report;

import com.neocat.common.time.bucket.Bucket;

import java.time.Instant;
import java.util.List;

import org.apache.commons.collections4.CollectionUtils;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.ToString;
import org.springframework.modulith.NamedInterface;

/**
 * 解析结果。
 *
 * <p>from：查询起点（含）。
 * <p>to：查询终点（不含）。
 * <p>buckets：桶序列。
 */
@NamedInterface("query")
@Getter
@EqualsAndHashCode
@ToString
public class ResolvedRange {
    private final Instant from;

    private final Instant to;

    private final List<Bucket> buckets;

    public ResolvedRange(Instant from, Instant to, List<Bucket> buckets) {
        this.from = from;
        this.to = to;
        this.buckets = buckets;
    }

    public long bucketSeconds() {
        return CollectionUtils.isEmpty(buckets) ? 0 : buckets.get(0).totalSeconds();
    }

    public int pointCount() {
        return buckets.size();
    }
}
