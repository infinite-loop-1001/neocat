package com.neocat.analysis.infra.adapter;

import com.neocat.analysis.domain.analyzer.SlowThresholdProvider;
import com.neocat.platform.api.internal.PlatformReadModel;
import org.springframework.stereotype.Component;

@Component
public class PlatformSlowThresholdAdapter implements SlowThresholdProvider {
    private final PlatformReadModel platform;

    public PlatformSlowThresholdAdapter(PlatformReadModel platform) {
        this.platform = platform;
    }
    @Override
    public int urlMs() { return platform.slowThresholds().getUrlMs(); }
    @Override
    public int sqlMs() { return platform.slowThresholds().getSqlMs(); }
    @Override
    public int callMs() { return platform.slowThresholds().getCallMs(); }
    @Override
    public int cacheMs() { return platform.slowThresholds().getCacheMs(); }
}
