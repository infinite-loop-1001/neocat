package com.neocat.ingest.domain.idempotency;

import java.time.Duration;
import java.util.Optional;

/**
 * 幂等指纹窗口（进程内）。
 *
 * <p>实现可用 Caffeine 等带 TTL 的缓存；TTL 由 Apollo 配置
 * {@code neocat.ingest.idempotency-window-minutes} 控制（默认 120 分钟）。
 */
@org.springframework.modulith.NamedInterface("tree")
public interface IdempotencyStore {

    @org.springframework.lang.Nullable
    String fingerprintOf(String messageId);

    void remember(String messageId, String fingerprint, Duration ttl);
}
