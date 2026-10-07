package com.neocat.platform.domain.channel;

import java.util.List;

@org.springframework.modulith.NamedInterface("platform")

public interface ChannelConfigRepository {

    List<ChannelConfig> findAll();

    ChannelConfig save(ChannelConfig config);
}
