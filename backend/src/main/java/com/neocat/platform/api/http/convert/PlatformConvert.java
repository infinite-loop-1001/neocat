package com.neocat.platform.api.http.convert;

import com.neocat.platform.api.http.dto.PlatformDtos.*;
import com.neocat.platform.domain.channel.ChannelConfig;
import com.neocat.platform.domain.channel.ChannelType;
import com.neocat.platform.domain.profile.PlatformProfile;
import com.neocat.platform.domain.profile.SlowThresholds;
import java.time.ZoneId;
import java.util.List;
import java.util.Objects;

public final class PlatformConvert {
    private PlatformConvert() {
    }

    public static com.neocat.platform.domain.init.InitRequest init(InitRequest request) {
        return new com.neocat.platform.domain.init.InitRequest(ZoneId.of(request.getTimezone()),
                request.getAdminUsername(), request.getAdminPassword());
    }

    public static SlowThresholds thresholds(SlowThresholdsRequest request) {
        return new SlowThresholds(request.getUrl(), request.getSql(), request.getCall(), request.getCache());
    }

    public static List<ChannelConfig> channels(ChannelsRequest request) {
        return List.of(new ChannelConfig(ChannelType.EMAIL, request.isEmail(), null),
                new ChannelConfig(ChannelType.DINGTALK, request.isDingtalk(), null),
                new ChannelConfig(ChannelType.FEISHU, request.isFeishu(), null));
    }

    public static ProfileResponse profile(PlatformProfile profile, List<ChannelConfig> configs) {
        SlowThresholds slow = profile.getSlowThresholds();
        return new ProfileResponse(profile.getTimezone().getId(), profile.isInitialized(),
                new SlowThresholdsRequest(slow.getUrlMs(), slow.getSqlMs(), slow.getCallMs(), slow.getCacheMs()),
                new ChannelsRequest(available(configs, ChannelType.EMAIL), available(configs, ChannelType.DINGTALK),
                        available(configs, ChannelType.FEISHU)));
    }

    private static boolean available(List<ChannelConfig> configs, ChannelType type) {
        return configs.stream().anyMatch(config -> Objects.equals(config.getType(), type) && config.isEnabled());
    }
}
