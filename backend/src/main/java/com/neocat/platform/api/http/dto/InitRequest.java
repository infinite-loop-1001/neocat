package com.neocat.platform.api.http.dto;

import lombok.AllArgsConstructor;
import lombok.Getter;
import io.swagger.v3.oas.annotations.media.Schema;

@Schema(name = "PlatformInitRequest", description = "平台初始化请求；只在未初始化时可用")
@Getter
@AllArgsConstructor
public class InitRequest {
    @Schema(description = "平台时区 ID，如 Asia/Shanghai；初始化后不可修改",
            requiredMode = Schema.RequiredMode.REQUIRED)
    private final String timezone;

    @Schema(description = "初始超级管理员登录名", requiredMode = Schema.RequiredMode.REQUIRED)
    private final String adminUsername;

    @Schema(description = "初始超级管理员口令", requiredMode = Schema.RequiredMode.REQUIRED)
    private final String adminPassword;
}
