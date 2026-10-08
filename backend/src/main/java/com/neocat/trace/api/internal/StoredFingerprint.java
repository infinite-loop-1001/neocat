package com.neocat.trace.api.internal;

import org.springframework.lang.Nullable;

/** Read-only historical fingerprint lookup for ingestion deduplication. */
public interface StoredFingerprint {
    @Nullable
    String fingerprintOf(String messageId);
}
