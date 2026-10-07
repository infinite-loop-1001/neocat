package com.neocat.platform.api.http.dto;

import lombok.AllArgsConstructor;
import lombok.Getter;

public final class PlatformDtos {
    private PlatformDtos() {
    }

    @Getter
    @AllArgsConstructor
    public static class InitRequest {
        private final String timezone;

        private final String adminUsername;

        private final String adminPassword;
    }

    @Getter
    @AllArgsConstructor
    public static class SlowThresholdsRequest {
        private final int url;

        private final int sql;

        private final int call;

        private final int cache;
    }

    @Getter
    @AllArgsConstructor
    public static class ChannelsRequest {
        private final boolean email;

        private final boolean dingtalk;

        private final boolean feishu;
    }

    @Getter
    @AllArgsConstructor
    public static class InitStatus {
        private final boolean initialized;
    }

    @Getter
    @AllArgsConstructor
    public static class ProfileResponse {
        private final String timezone;

        private final boolean initialized;

        private final SlowThresholdsRequest slow;

        private final ChannelsRequest channels;
    }
}
