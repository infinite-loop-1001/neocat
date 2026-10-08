package com.neocat.platform.domain.channel;

import java.util.List;
import org.springframework.modulith.NamedInterface;

@NamedInterface("platform")
public interface ChannelConfigRepository {

    List<ChannelConfig> findAll();

    ChannelConfig save(ChannelConfig config);
}
