package com.neocat.common.time.bucket;

import java.time.Duration;

/**
 * 时间桶粒度。
 * 与 PRD 03 的固定周期和快捷范围一一对应，粒度定义不可随意改动。
 */
@org.springframework.modulith.NamedInterface("time")
public enum Granularity {

    MINUTE_1(Duration.ofMinutes(1)),
    MINUTE_5(Duration.ofMinutes(5)),
    MINUTE_10(Duration.ofMinutes(10)),
    MINUTE_20(Duration.ofMinutes(20)),
    HOUR_1(Duration.ofHours(1)),
    DAY_1(Duration.ofDays(1));

    private final Duration duration;

    Granularity(Duration duration) {
        this.duration = duration;
    }
    public Duration duration() {
        return duration;
    }
    public long seconds() {
        return duration.getSeconds();
    }
    /**
     * 按秒数反查粒度；没有对应档位时返回 {@code null}。
     *
     * <p>桶长只能取本枚举已定义的档位——否则桶边界无法与平台时区对齐。
     * 调用方（如 `bucket` 参数解析、数据层选择源表）都要用同一份映射，
     * 避免各自维护换算表而产生分歧。
     */
    public static Granularity fromSeconds(long seconds) {
        for (Granularity candidate : values()) {
            if (candidate.seconds() == seconds) {
                return candidate;
            }
        }
        return null;
    }
}
