package com.neocat.alert.api.http.dto.channel;

import lombok.AllArgsConstructor;
import lombok.Getter;
import io.swagger.v3.oas.annotations.media.Schema;

@Schema(name = "AlertChannelResponse", description = "通知通道及可用标记")
@Getter
@AllArgsConstructor
public class ChannelResponse {
    @Schema(description = "通道名：EMAIL | DINGTALK | FEISHU")
    private final String channel;

    @Schema(description = "当前是否可投递")
    private final boolean available;
}
