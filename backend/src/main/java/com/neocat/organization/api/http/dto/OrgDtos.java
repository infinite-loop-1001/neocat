package com.neocat.organization.api.http.dto;

import lombok.AllArgsConstructor;
import lombok.Getter;
import java.util.List;
import com.fasterxml.jackson.annotation.JsonCreator;

public final class OrgDtos {
    private OrgDtos() {
    }

    @Getter
    @AllArgsConstructor
    public static class OrgDraft {
        private final String name;

        private final Long parentId;
    }

    @Getter
    @AllArgsConstructor(onConstructor_ = @JsonCreator(mode = JsonCreator.Mode.PROPERTIES))
    public static class MemberDraft {
        private final long userId;
    }

    @Getter
    @AllArgsConstructor
    public static class OrgResponse {
        private final long id;

        private final String name;

        private final Long parentId;

        private final boolean leaf;

        private final int memberCount;
    }

    @Getter
    @AllArgsConstructor
    public static class DashboardSummary {
        private final long id;

        private final String name;

        private final long cardCount;
    }

    @Getter
    @AllArgsConstructor
    public static class DeletionResponse {
        private final String orgName;

        private final List<DashboardSummary> dashboards;

        private final long alertRuleCount;

        private final long memberCount;
    }

    @Getter
    @AllArgsConstructor
    public static class Success {
        private final boolean ok;
    }
}
