package com.neocat.dashboard.api.http.dto;

import java.math.BigDecimal;

import lombok.AllArgsConstructor;
import lombok.Getter;

import java.util.List;

public final class DashboardDtos {
    private DashboardDtos() {
    }

    @Getter
    @AllArgsConstructor
    public static class DashboardDraft {
        private final long orgId;

        private final String name;
    }

    @Getter
    @AllArgsConstructor
    public static class CardDraft {
        private final String service;

        private final String targetKind;

        private final String targetType;

        private final String targetName;

        private final String metricLabels;

        private final String formula;

        private final String timeRange;

        private final List<Threshold> thresholdLines;
    }

    @Getter
    @AllArgsConstructor
    public static class DashboardResponse {
        private final long id;

        private final long orgId;

        private final String name;
    }

    @Getter
    @AllArgsConstructor
    public static class Threshold {
        private final String direction;

        private final BigDecimal value;

    }

    @Getter
    @AllArgsConstructor
    public static class CardResponse {
        private final long id;

        private final long dashboardId;

        private final String service;

        private final String targetKind;

        private final String targetType;

        private final String targetName;

        private final String metricLabels;

        private final String formula;

        private final String timeRange;

        private final List<Threshold> thresholdLines;

        private final String unit;
    }

    @Getter
    @AllArgsConstructor
    public static class TargetResponse {
        private final String kind;

        private final long cardId;

        private final String service;

        private final String targetKind;

        private final String targetType;

        private final String targetName;

        private final List<String> stats;
    }

    @Getter
    @AllArgsConstructor
    public static class SeriesPoint {
        private final long bucketStart;

        private final long bucketEnd;

        private final BigDecimal value;

        private final String outcome;
    }

    @Getter
    @AllArgsConstructor
    public static class Gap {
        private final long bucketStart;

        private final List<String> missingInputs;
    }

    @Getter
    @AllArgsConstructor
    public static class Undefined {
        private final long bucketStart;

        private final String reason;
    }

    @Getter
    @AllArgsConstructor
    public static class SeriesResponse {
        private final long cardId;

        private final String formula;

        private final String unit;

        private final List<Threshold> thresholdLines;

        private final List<SeriesPoint> points;

        private final List<Gap> gaps;

        private final List<Undefined> isUndefined;
    }

    @Getter
    @AllArgsConstructor
    public static class Success {
        private final boolean ok;
    }
}
