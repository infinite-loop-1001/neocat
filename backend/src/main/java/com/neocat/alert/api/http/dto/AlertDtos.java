package com.neocat.alert.api.http.dto;

import lombok.AllArgsConstructor;
import lombok.Getter;
import java.util.List;

/** 告警一期契约，无历史、严重度、确认字段。 */
// rules: 需要单独拆出文件, 不能都放到一个类里面
public final class AlertDtos {
    private AlertDtos() {
    }

    @Getter
    @AllArgsConstructor
    public static class AlertDraft {
        private final String scope;

        private final Long orgId;

        private final String name;

        private final String description;

        private final TargetDraft target;

        private final String combinator;

        private final int windowPoints;

        private final List<ConditionDraft> conditions;

        private final List<Long> recipients;

        private final List<String> channels;
    }

    @Getter
    @AllArgsConstructor
    public static class TargetDraft {
        private final String kind;

        private final long cardId;

        private final String service;

        private final String reportKind;

        private final String type;

        private final String name;

        private final List<String> formulaStats;
    }

    @Getter
    @AllArgsConstructor
    public static class ConditionDraft {
        private final String stat;

        private final String comparator;

        private final double threshold;
    }

    @Getter
    @AllArgsConstructor
    public static class TargetResponse {
        private final String kind;

        private final long cardId;

        private final String service;

        private final String reportKind;

        private final String type;

        private final String name;
    }

    @Getter
    @AllArgsConstructor
    public static class RuleResponse {
        private final long id;

        private final String scope;

        private final Long orgId;

        private final String name;

        private final String combinator;

        private final int windowPoints;

        private final boolean enabled;

        private final boolean invalid;

        private final List<Long> recipients;

        private final List<String> channels;

        private final List<ConditionDraft> conditions;

        private final TargetResponse target;
    }

    @Getter
    @AllArgsConstructor
    public static class PreviewPoint {
        private final long minute;

        private final boolean known;

        private final boolean satisfied;

        private final String missingStat;
    }

    @Getter
    @AllArgsConstructor
    public static class PreviewResponse {
        private final String result;

        private final List<PreviewPoint> points;
    }

    @Getter
    @AllArgsConstructor
    public static class ChannelResponse {
        private final String channel;

        private final boolean available;
    }

    @Getter
    @AllArgsConstructor
    public static class Success {
        private final boolean ok;
    }
}
