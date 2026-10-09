package com.neocat.identity.api.http.dto.auth;

import lombok.AllArgsConstructor;
import lombok.Getter;
import io.swagger.v3.oas.annotations.media.Schema;

@Schema(name = "LoginRequest", description = "登录请求")
@Getter
@AllArgsConstructor
public class LoginRequest {
    @Schema(description = "登录名", requiredMode = Schema.RequiredMode.REQUIRED)
    private final String username;

    @Schema(description = "口令（明文只在传输层出现，服务端只存哈希）",
            requiredMode = Schema.RequiredMode.REQUIRED)
    private final String password;
}
