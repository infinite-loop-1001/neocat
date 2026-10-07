package com.neocat.common.time.clock;

import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.Clock;

@Component
@org.springframework.modulith.NamedInterface("time")
public class SystemClockProvider implements ClockProvider {
    private final Clock clock;

    public SystemClockProvider(Clock clock) {
        this.clock = clock;
    }
    @Override
    public Instant now() {
        return clock.instant();
    }
}
