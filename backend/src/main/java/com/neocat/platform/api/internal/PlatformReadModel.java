package com.neocat.platform.api.internal;
import com.neocat.platform.api.internal.result.Thresholds;

public interface PlatformReadModel {

    Thresholds slowThresholds();

    boolean channelEnabled(String channel);
}
