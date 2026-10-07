package com.neocat.dashboard.api.internal;

import java.time.Instant;

/** Resolve the current card formula for a single complete report minute. */
public interface CardResults {
    Double value(long cardId, Instant from, Instant to);
}
