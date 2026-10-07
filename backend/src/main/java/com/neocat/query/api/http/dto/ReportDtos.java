package com.neocat.query.api.http.dto;

import lombok.Getter;
import lombok.Setter;
import java.util.List;

/** 固定字段读模型；保留原契约的 null 与字段存在性。 */
public final class ReportDtos {
    private ReportDtos() {
    }

    @Getter
    @Setter
    public static class TableRow {
        private String type;

        private String name;

        private long total;

        private long failures;

        private Double failureRate;

        private Double qps;

        private Long min;

        private Long max;

        private Double avg;

        private Double tp50;

        private Double tp90;

        private Double tp95;

        private Double tp99;

        private Double tp999;

        private Double tp9999;
    }

    @Getter
    @Setter
    public static class ProblemCategory {
        private String category;

        private long total;

        private boolean supportsPercentile;
    }

    @Getter
    @Setter
    public static class ProblemName {
        private String name;

        private long total;

        private Double tp99;
    }

    @Getter
    @Setter
    public static class Point {
        private long bucketStart;

        private long bucketEnd;

        private Double value;

        private String quality;

        private long coveredSeconds;

        private boolean realtime;

        private boolean partial;
    }

    @Getter
    @Setter
    public static class HeartbeatPoint {
        private long bucketStart;

        private long bucketEnd;

        private Double value;

        private String quality;

        private long coveredSeconds;
    }

    @Getter
    @Setter
    public static class Range {
        private long from;

        private long to;
    }

    @Getter
    @Setter
    public static class MomPoint {
        private long bucketStart;

        private Double value;
    }

    @Getter
    @Setter
    public static class Mom {
        private String kind;

        private List<MomPoint> points;
    }

    @Getter
    @Setter
    public static class Series {
        private String service;

        private String kind;

        private String type;

        private String name;

        private String stat;

        private String unit;

        private long bucketSeconds;

        private Range range;

        private List<Point> points;

        private Mom mom;
    }

    @Getter
    @Setter
    public static class HeartbeatInstance {
        private String instance;

        private Double value;
    }

    @Getter
    @Setter
    public static class InstanceSeries {
        private String instance;

        private List<HeartbeatPoint> points;
    }

    @Getter
    @Setter
    public static class HeartbeatSeries {
        private String service;

        private String metric;

        private long bucketSeconds;

        private List<InstanceSeries> series;

        private Mom mom;
    }

    @Getter
    @Setter
    public static class MetricRank {
        private String labels;

        private long reportCount;

        private int rank;
    }

    @Getter
    @Setter
    public static class Dependency {
        private String peer;

        private long calls;

        private Double failureRate;

        private Double avg;

        private Double tp99;
    }

    @Getter
    @Setter
    public static class Sample {
        private String messageId;

        private long timestamp;

        private long durationMs;

        private String status;

        private String summary;

        private boolean traceAvailable;
    }

    @Getter
    @Setter
    public static class MetricName {
        private String name;
    }

    @Getter
    @Setter
    public static class MetricLabel {
        private String key;

        private List<String> values;
    }

    @Getter
    @Setter
    public static class MetricCount {
        private String metric;

        private long bucketSeconds;

        private List<CountPoint> points;
    }

    @Getter
    @Setter
    public static class CountPoint {
        private long bucketStart;

        private long bucketEnd;

        private Long value;

        private String quality;

        private long coveredSeconds;

        private boolean realtime;

        private boolean partial;
    }
}
