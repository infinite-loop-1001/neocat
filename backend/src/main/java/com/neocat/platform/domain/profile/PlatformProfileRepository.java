package com.neocat.platform.domain.profile;

import java.util.Optional;

@org.springframework.modulith.NamedInterface("platform")

public interface PlatformProfileRepository {

    @org.springframework.lang.Nullable
    PlatformProfile load();

    void save(PlatformProfile profile);
}
