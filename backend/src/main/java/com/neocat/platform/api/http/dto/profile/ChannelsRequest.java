package com.neocat.platform.api.http.dto.profile;

import lombok.AllArgsConstructor;
import lombok.Getter;
import io.swagger.v3.oas.annotations.media.Schema;

@Schema(name = "ChannelsRequest", description = "通知通道开关；未启用的通道不出现在告警可选通道中")
@Getter
@AllArgsConstructor
public class ChannelsRequest {
    @Schema(description = "邮件通道是否启用")
    private final boolean email;

    @Schema(description = "钉钉通道是否启用")
    private final boolean dingtalk;

    @Schema(description = "飞书通道是否启用")
    private final boolean feishu;
}
