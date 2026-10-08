package com.neocat.platform.api.http.dto;

import lombok.AllArgsConstructor;
import lombok.Getter;
import io.swagger.v3.oas.annotations.media.Schema;

@Schema(name = "PlatformDtos", description = "平台配置请求、响应契约容器")
public final class PlatformDtos {
    private PlatformDtos() {
    }

    @Schema(name = "PlatformInitRequest", description = "平台初始化请求；只在未初始化时可用")
    @Getter
    @AllArgsConstructor
    public static class InitRequest {
        @Schema(description = "平台时区 ID，如 Asia/Shanghai；初始化后不可修改",
                requiredMode = Schema.RequiredMode.REQUIRED)
        private final String timezone;

        @Schema(description = "初始超级管理员登录名", requiredMode = Schema.RequiredMode.REQUIRED)
        private final String adminUsername;

        @Schema(description = "初始超级管理员口令", requiredMode = Schema.RequiredMode.REQUIRED)
        private final String adminPassword;
    }

    @Schema(name = "SlowThresholdsRequest", description = "慢阈值（毫秒）；只影响后续分析，不回算历史")
    @Getter
    @AllArgsConstructor
    public static class SlowThresholdsRequest {
        @Schema(description = "URL 慢阈值（毫秒）")
        private final int url;

        @Schema(description = "SQL 慢阈值（毫秒）")
        private final int sql;

        @Schema(description = "远程调用慢阈值（毫秒）")
        private final int call;

        @Schema(description = "缓存访问慢阈值（毫秒）")
        private final int cache;
    }

    @Schema(name = "ChannelsRequest", description = "通知通道开关；未启用的通道不出现在告警可选通道中")
    @Getter
    @AllArgsConstructor
    public static class ChannelsRequest {
        @Schema(description = "邮件通道是否启用")
        private final boolean email;

        @Schema(description = "钉钉通道是否启用")
        private final boolean dingtalk;

        @Schema(description = "飞书通道是否启用")
        private final boolean feishu;
    }

    @Schema(name = "PlatformInitStatus", description = "平台初始化状态")
    @Getter
    @AllArgsConstructor
    public static class InitStatus {
        @Schema(description = "是否已完成初始化")
        private final boolean initialized;
    }

    @Schema(name = "PlatformProfile", description = "平台档案；全部时间字段按 timezone 解释")
    @Getter
    @AllArgsConstructor
    public static class ProfileResponse {
        @Schema(description = "平台时区 ID")
        private final String timezone;

        @Schema(description = "是否已完成初始化")
        private final boolean initialized;

        @Schema(description = "慢阈值（毫秒）")
        private final SlowThresholdsRequest slow;

        @Schema(description = "通知通道开关")
        private final ChannelsRequest channels;
    }
}
