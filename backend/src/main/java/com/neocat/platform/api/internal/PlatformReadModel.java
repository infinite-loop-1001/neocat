package com.neocat.platform.api.internal;

public interface PlatformReadModel {

    Thresholds slowThresholds();

    boolean channelEnabled(String channel);
}
