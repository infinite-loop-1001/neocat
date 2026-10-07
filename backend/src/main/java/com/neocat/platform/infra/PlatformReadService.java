package com.neocat.platform.infra;

import com.neocat.platform.api.internal.PlatformReadModel;
import com.neocat.platform.domain.channel.ChannelConfigRepository;
import com.neocat.platform.domain.channel.ChannelType;
import com.neocat.platform.domain.profile.PlatformProfileRepository;
import com.neocat.platform.domain.profile.SlowThresholds;
import org.springframework.stereotype.Component;

import java.util.Objects;

@Component
public class PlatformReadService implements PlatformReadModel {
    private final PlatformProfileRepository profiles;

    private final ChannelConfigRepository channels;

    public PlatformReadService(PlatformProfileRepository profiles, ChannelConfigRepository channels) {
        this.profiles = profiles;
        this.channels = channels;
    }
    @Override
    public Thresholds slowThresholds() {
        // Analysis beans are created before the first administrator can initialize the platform.
        // The pre-initialization thresholds are part of the platform domain, not an Apollo fallback.
        var thresholds = java.util.Optional.ofNullable(profiles.load()).map(p -> p.getSlowThresholds())
                .orElseGet(SlowThresholds::defaults);
        return new Thresholds(thresholds.getUrlMs(), thresholds.getSqlMs(), thresholds.getCallMs(), thresholds.getCacheMs());
    }
    @Override
    public boolean channelEnabled(String channel) {
        ChannelType type = ChannelType.valueOf(channel);
        return channels.findAll().stream().anyMatch(c -> c.getType() == type && c.isEnabled()
                && Objects.nonNull(c.getConfig()) && !c.getConfig().isBlank());
    }
}
