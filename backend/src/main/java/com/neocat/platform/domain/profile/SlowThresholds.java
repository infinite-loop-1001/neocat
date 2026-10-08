package com.neocat.platform.domain.profile;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.ToString;
import org.springframework.modulith.NamedInterface;

/**
 * 平台慢阈值（PRD 01 §2.1 / PRD 03 §9）。
 *
 * <p>默认值：URL 1000ms、SQL 100ms、调用 1000ms、缓存 50ms。
 * 阈值只影响后续处理，不重算历史。
 */
@NamedInterface("platform")
@Getter
@EqualsAndHashCode
@ToString
public class SlowThresholds {
    private final int urlMs;

    private final int sqlMs;

    private final int callMs;

    private final int cacheMs;

    public SlowThresholds(int urlMs, int sqlMs, int callMs, int cacheMs) {
        this.urlMs = urlMs;
        this.sqlMs = sqlMs;
        this.callMs = callMs;
        this.cacheMs = cacheMs;
    }

    public static SlowThresholds defaults() {
        return new SlowThresholds(1000, 100, 1000, 50);
    }
}
