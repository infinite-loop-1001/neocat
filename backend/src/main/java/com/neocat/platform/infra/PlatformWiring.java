package com.neocat.platform.infra;

import com.neocat.platform.domain.profile.PlatformProfile;
import com.neocat.platform.domain.profile.PlatformProfileRepository;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import java.util.function.Supplier;
@org.springframework.context.annotation.Configuration
public class PlatformWiring {

    /**
     * 平台时区供应者：由平台档案决定，初始化后不可变（PRD 00 §12）。
     */
    @Bean
    public Supplier<java.time.ZoneId> platformZone(
            com.neocat.platform.domain.profile.PlatformProfileRepository profiles,
            @Value("${neocat.platform.init.timezone}") String fallbackTimezone) {
        return () -> java.util.Optional.ofNullable(profiles.load())
                .filter(com.neocat.platform.domain.profile.PlatformProfile::isInitialized)
                .map(com.neocat.platform.domain.profile.PlatformProfile::getTimezone)
                .orElseGet(() -> java.time.ZoneId.of(fallbackTimezone));
    }
}
