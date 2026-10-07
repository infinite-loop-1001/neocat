package com.neocat.alert.infra.adapter;

import com.neocat.alert.domain.rule.AlertChannel;
import com.neocat.alert.domain.engine.ChannelAvailability;
import com.neocat.platform.api.internal.PlatformReadModel;
import org.springframework.stereotype.Component;

@Component
public class ConfiguredChannelAvailability implements ChannelAvailability {
    private final PlatformReadModel platform;

    public ConfiguredChannelAvailability(PlatformReadModel platform) {
        this.platform = platform;
    }
    @Override
    public boolean available(AlertChannel channel) {
        return platform.channelEnabled(channel.name());
    }
}
