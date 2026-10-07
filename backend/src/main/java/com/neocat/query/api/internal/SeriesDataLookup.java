package com.neocat.query.api.internal;

import java.time.Instant;

public interface SeriesDataLookup {
    boolean hasData(String service, String kind, String instance, Instant from, Instant to);
}
