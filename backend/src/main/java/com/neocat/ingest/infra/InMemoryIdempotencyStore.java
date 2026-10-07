package com.neocat.ingest.infra;

import com.neocat.ingest.domain.idempotency.IdempotencyStore;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.Objects;

/**
 * 进程内幂等窗口实现（技术方案 01-architecture.md §6、07 §1.2）。
 *
 * <p>用 {@code ConcurrentHashMap} + 到期时间戳实现带 TTL 的窗口，
 * 避免引入额外依赖；窗口大小由 Apollo 键
 * {@code neocat.ingest.idempotency-window-minutes} 控制（默认 120 分钟）。
 *
 * <p>窗口外的重复 ID 由 {@code HistoricalFingerprintLookup} 兜底（ClickHouse 精确查询）。
 * 因此在窗口内不需要保存完整历史，内存占用可预测。
 */
@org.springframework.stereotype.Component
public class InMemoryIdempotencyStore implements IdempotencyStore {

    @lombok.Getter
    @lombok.EqualsAndHashCode
    @lombok.ToString
    private static class Entry {
        private final String fingerprint;

        private final Instant expiresAt;

        public Entry(String fingerprint, Instant expiresAt) {
            this.fingerprint = fingerprint;
            this.expiresAt = expiresAt;
        }

    }
    private final Map<String, Entry> entries;

    private final java.time.Clock clock;

    public InMemoryIdempotencyStore() {
        this(java.time.Clock.systemUTC());
    }

    @org.springframework.beans.factory.annotation.Autowired
    public InMemoryIdempotencyStore(java.time.Clock clock) {
        this.clock = clock;
        this.entries = new ConcurrentHashMap<>();
    }

    @Override
    public String fingerprintOf(String messageId) {
        Entry entry = entries.get(messageId);
        if (Objects.isNull(entry)) {
            return null;
        }
        if (!entry.getExpiresAt().isAfter(clock.instant())) {
            // 惰性清理：读取时发现过期即移除，避免后台清理线程
            entries.remove(messageId, entry);
            return null;
        }
        return entry.getFingerprint();
    }
    @Override
    public void remember(String messageId, String fingerprint, Duration ttl) {
        entries.put(messageId, new Entry(fingerprint, clock.instant().plus(ttl)));
    }
    /** 当前窗口内的条目数（用于观测与测试）。 */
    public int size() {
        return entries.size();
    }
}
