package com.neocat.ingest.infra;

import com.neocat.ingest.domain.idempotency.HistoricalFingerprintLookup;
import com.neocat.trace.api.internal.StoredFingerprint;
import org.springframework.stereotype.Component;

import java.util.Optional;

@Component
public class HistoricalFingerprintAdapter implements HistoricalFingerprintLookup {
    private final StoredFingerprint stored;

    public HistoricalFingerprintAdapter(StoredFingerprint stored) {
        this.stored = stored;
    }
    @Override
    public String fingerprintOf(String messageId) {
        return stored.fingerprintOf(messageId);
    }
}
