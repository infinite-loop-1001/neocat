package com.neocat.platform.infra;

import com.neocat.platform.domain.profile.PlatformProfile;
import com.neocat.platform.domain.profile.PlatformProfileRepository;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import java.util.function.Supplier;
import java.time.ZoneId;
import java.util.Optional;
import org.springframework.context.annotation.Configuration;
@Configuration
public class PlatformWiring {

    /**
     * 平台时区供应者：由平台档案决定，初始化后不可变（PRD 00 §12）。
     */
    @Bean
    public Supplier<ZoneId> platformZone(
            PlatformProfileRepository profiles,
            @Value("${neocat.platform.init.timezone}") String fallbackTimezone) {
        return () -> Optional.ofNullable(profiles.load())
                .filter(PlatformProfile::isInitialized)
                .map(PlatformProfile::getTimezone)
                .orElseGet(() -> ZoneId.of(fallbackTimezone));
    }
}
