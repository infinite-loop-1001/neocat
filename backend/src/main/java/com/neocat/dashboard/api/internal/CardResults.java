package com.neocat.dashboard.api.internal;

import java.math.BigDecimal;

import java.time.Instant;

/**
 * Resolve the current card formula for a single complete report minute.
 */
public interface CardResults {
    BigDecimal value(long cardId, Instant from, Instant to);
}
