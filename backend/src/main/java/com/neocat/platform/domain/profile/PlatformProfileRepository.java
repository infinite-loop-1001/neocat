package com.neocat.platform.domain.profile;

import org.springframework.lang.Nullable;
import org.springframework.modulith.NamedInterface;

@NamedInterface("platform")
public interface PlatformProfileRepository {

    @Nullable
    PlatformProfile load();

    void save(PlatformProfile profile);
}
