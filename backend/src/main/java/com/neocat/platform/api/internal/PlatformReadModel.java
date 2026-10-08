package com.neocat.platform.api.internal;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.ToString;

public interface PlatformReadModel {

    @Getter
    @EqualsAndHashCode
    @ToString
    class Thresholds {
        private final int urlMs;

        private final int sqlMs;

        private final int callMs;

        private final int cacheMs;

        public Thresholds(int urlMs, int sqlMs, int callMs, int cacheMs) {
            this.urlMs = urlMs;
            this.sqlMs = sqlMs;
            this.callMs = callMs;
            this.cacheMs = cacheMs;
        }
    }

    Thresholds slowThresholds();
    boolean channelEnabled(String channel);
}
