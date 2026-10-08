package com.neocat.ingest.domain.idempotency;

import java.time.Duration;
import java.util.Objects;

import com.neocat.ingest.config.IngestConfig;
import org.springframework.context.annotation.DependsOn;
import org.springframework.modulith.NamedInterface;
import org.springframework.stereotype.Service;

/**
 * 上报幂等用例（PRD 02 §6.1）。
 *
 * <p>判定顺序：进程内窗口（TTL 由 Apollo 控制）→ 历史指纹兜底（ClickHouse 7 天内精确）→ NEW。
 * 只有判定为 {@link IdempotencyDecision#NEW} 时才写入窗口记忆，
 * 这样 DUPLICATE 与 CONFLICT 都不会污染窗口（也不会增加任何统计）。
 */
@NamedInterface("tree")
@Service
@DependsOn("ingestConfig")
public class IdempotencyService {

    private final IdempotencyStore store;

    private final HistoricalFingerprintLookup historical;

    public IdempotencyService(IdempotencyStore store, HistoricalFingerprintLookup historical) {
        this.store = store;
        this.historical = historical;
    }
    public IdempotencyDecision decide(String messageId, String fingerprint) {
        if (Objects.isNull(messageId) || messageId.isBlank()) {
            throw new IllegalArgumentException("messageId 不能为空");
        }
        String cached = store.fingerprintOf(messageId);
        if (Objects.nonNull(cached)) {
            return Objects.equals(cached, fingerprint)
                    ? IdempotencyDecision.DUPLICATE
                    : IdempotencyDecision.CONFLICT;
        }

        String past = historical.fingerprintOf(messageId);
        if (Objects.nonNull(past)) {
            return Objects.equals(past, fingerprint)
                    ? IdempotencyDecision.DUPLICATE
                    : IdempotencyDecision.CONFLICT;
        }

        store.remember(messageId, fingerprint, Duration.ofMinutes(IngestConfig.IDEMPOTENCY_WINDOW_MINUTES));
        return IdempotencyDecision.NEW;
    }
}
