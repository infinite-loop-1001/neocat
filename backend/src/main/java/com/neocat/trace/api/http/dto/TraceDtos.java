package com.neocat.trace.api.http.dto;

import lombok.AllArgsConstructor;
import lombok.Getter;
import java.util.List;

public final class TraceDtos {
    private TraceDtos() {
    }

    @Getter
    @AllArgsConstructor
    public static class TraceResponse {
        private final String messageId;

        private final boolean expired;

        private final List<Node> children;

        private final long missingNodes;

        private final long expiredNodes;
    }

    @Getter
    @AllArgsConstructor
    public static class Node {
        private final String messageId;

        private final String service;

        private final String instance;

        private final String availability;

        private final String reason;

        private final Long treeTimestamp;

        private final List<Span> spans;

        private final List<Node> children;
    }

    @Getter
    @AllArgsConstructor
    public static class Span {
        private final String nodeId;

        private final String kind;

        private final String category;

        private final String name;

        private final String status;

        private final long durationMs;

        private final String detail;

        private final long timestamp;
    }
}
