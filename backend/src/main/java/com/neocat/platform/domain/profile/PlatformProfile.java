package com.neocat.platform.domain.profile;

import java.time.Instant;
import java.time.ZoneId;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.ToString;
import org.springframework.modulith.NamedInterface;

/**
 * 平台档案（PRD 01 §2）：单行记录，保存固定时区、慢阈值与初始化状态。
 *
 * <p>时区在初始化设定后一期不可修改（PRD 00 §12）。
 */
@NamedInterface("platform")
@Getter
@EqualsAndHashCode
@ToString
public class PlatformProfile {
    private final boolean initialized;

    private final ZoneId timezone;

    private final SlowThresholds slowThresholds;

    private final Instant initializedAt;

    public PlatformProfile(boolean initialized, ZoneId timezone, SlowThresholds slowThresholds, Instant initializedAt) {
        this.initialized = initialized;
        this.timezone = timezone;
        this.slowThresholds = slowThresholds;
        this.initializedAt = initializedAt;
    }

    public static PlatformProfile notInitialized() {
        return new PlatformProfile(false, ZoneId.of("Asia/Shanghai"), SlowThresholds.defaults(), null);
    }
    public PlatformProfile withInitialized(ZoneId zone, Instant at) {
        return new PlatformProfile(true, zone, slowThresholds, at);
    }
    public PlatformProfile withSlowThresholds(SlowThresholds thresholds) {
        return new PlatformProfile(initialized, timezone, thresholds, initializedAt);
    }
}
