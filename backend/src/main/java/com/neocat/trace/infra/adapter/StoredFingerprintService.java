package com.neocat.trace.infra.adapter;

import com.neocat.trace.api.internal.StoredFingerprint;
import com.neocat.trace.domain.tree.RawTreeStore;
import org.springframework.stereotype.Component;

import java.util.Optional;

@Component
public class StoredFingerprintService implements StoredFingerprint {
    private final RawTreeStore store;

    public StoredFingerprintService(RawTreeStore store) {
        this.store = store;
    }
    @Override
    public String fingerprintOf(String messageId) {
        return store.fingerprintOf(messageId);
    }
}
