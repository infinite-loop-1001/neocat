package com.neocat.common.time.range;

import com.neocat.common.time.bucket.Granularity;
import org.springframework.modulith.NamedInterface;

/**
 * 快捷范围（技术方案 03-api-contract.md §4.1）。
 *
 * <p>每个快捷范围携带默认粒度，避免「最近 24 小时按分钟出桶」这类退化。
 */
@NamedInterface("time")
public enum RangeQuick {
    RECENT_1H(Granularity.MINUTE_1),
    RECENT_3H(Granularity.MINUTE_5),
    RECENT_6H(Granularity.MINUTE_10),
    RECENT_12H(Granularity.MINUTE_20),
    RECENT_24H(Granularity.HOUR_1),
    TODAY(Granularity.MINUTE_10),
    THIS_WEEK(Granularity.HOUR_1);

    private final Granularity granularity;

    RangeQuick(Granularity granularity) {
        this.granularity = granularity;
    }
    public Granularity granularity() {
        return granularity;
    }
}
