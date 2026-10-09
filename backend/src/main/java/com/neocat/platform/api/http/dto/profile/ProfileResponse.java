package com.neocat.platform.api.http.dto.profile;

import lombok.AllArgsConstructor;
import lombok.Getter;
import io.swagger.v3.oas.annotations.media.Schema;

@Schema(name = "PlatformProfile", description = "平台档案；全部时间字段按 timezone 解释")
@Getter
@AllArgsConstructor
public class ProfileResponse {
    @Schema(description = "平台时区 ID")
    private final String timezone;

    @Schema(description = "是否已完成初始化")
    private final boolean initialized;

    @Schema(description = "慢阈值（毫秒）")
    private final SlowThresholdsRequest slow;

    @Schema(description = "通知通道开关")
    private final ChannelsRequest channels;
}
