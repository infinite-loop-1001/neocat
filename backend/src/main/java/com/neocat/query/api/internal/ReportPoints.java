package com.neocat.query.api.internal;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/** Null values mean missing measurements, never silently turn them into zero. */
public interface ReportPoints {
    Map<String, Double> values(String kind, String service, String type, String name,
                               Instant from, Instant to, List<String> stats, List<String> instances);
}
