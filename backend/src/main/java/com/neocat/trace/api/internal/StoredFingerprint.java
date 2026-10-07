package com.neocat.trace.api.internal;

import java.util.Optional;

/** Read-only historical fingerprint lookup for ingestion deduplication. */
public interface StoredFingerprint {
    @org.springframework.lang.Nullable
    String fingerprintOf(String messageId);
}
