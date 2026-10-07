package com.neocat.common.time.clock;

import java.time.Instant;

/**
 * 时钟提供者。领域代码禁止直接调用 {@code Instant.now()}，以便时间桶与告警窗口可测。
 */
@FunctionalInterface
@org.springframework.modulith.NamedInterface("time")
public interface ClockProvider {

    Instant now();
}
