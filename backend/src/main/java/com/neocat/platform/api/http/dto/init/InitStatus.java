package com.neocat.platform.api.http.dto.init;

import lombok.AllArgsConstructor;
import lombok.Getter;
import io.swagger.v3.oas.annotations.media.Schema;

@Schema(name = "PlatformInitStatus", description = "平台初始化状态")
@Getter
@AllArgsConstructor
public class InitStatus {
    @Schema(description = "是否已完成初始化")
    private final boolean initialized;
}
