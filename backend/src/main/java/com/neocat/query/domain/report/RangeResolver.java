package com.neocat.query.domain.report;

import com.google.common.collect.Lists;
import com.neocat.common.time.bucket.Bucket;
import com.neocat.common.time.bucket.Granularity;
import com.neocat.common.time.range.RangeSpec;
import com.neocat.common.time.bucket.TimeBucketResolver;

import java.time.Instant;
import java.time.ZoneId;
import java.util.List;

import org.apache.commons.collections4.CollectionUtils;

import java.time.Duration;

import org.springframework.modulith.NamedInterface;

/**
 * 查询时间范围解析（PRD 03 §2、技术方案 03 §4.2）。
 *
 * <p>把外部传入的范围描述解析为左闭右开的桶序列，并同时给出查询边界。
 * 边界解析与粒度选择全部委托给 {@link TimeBucketResolver}，本类只负责
 * 「范围描述 → RangeSpec」的适配与结果封装，避免两处实现粒度规则而产生分歧。
 */
@NamedInterface("query")
public class RangeResolver {

    private final TimeBucketResolver buckets;

    public RangeResolver(TimeBucketResolver buckets) {
        this.buckets = buckets;
    }

    public ResolvedRange resolve(RangeSpec spec, ZoneId zone) {
        List<Bucket> parsed = buckets.resolve(spec, zone);
        if (CollectionUtils.isEmpty(parsed)) {
            return new ResolvedRange(Instant.EPOCH, Instant.EPOCH, Lists.newArrayList());
        }
        Instant from = parsed.get(0).getStart();
        Instant to = parsed.get(parsed.size() - 1).getEnd();
        return new ResolvedRange(from, to, parsed);
    }

    /**
     * 按范围长度推导默认粒度（用于显式 from/to 且未指定粒度的情况）。
     *
     * <p>与技术方案 03 §4.2 的快捷范围粒度保持一致。
     */
    public Granularity inferredGranularity(Duration length) {
        long seconds = length.getSeconds();
        if (seconds <= 3600) {
            return Granularity.MINUTE_1;
        }
        if (seconds <= 3 * 3600) {
            return Granularity.MINUTE_5;
        }
        if (seconds <= 6 * 3600) {
            return Granularity.MINUTE_10;
        }
        if (seconds <= 12 * 3600) {
            return Granularity.MINUTE_20;
        }
        if (seconds <= 24 * 3600) {
            return Granularity.HOUR_1;
        }
        return Granularity.DAY_1;
    }
}
