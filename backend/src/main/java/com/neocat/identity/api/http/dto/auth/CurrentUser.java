package com.neocat.identity.api.http.dto.auth;

import lombok.AllArgsConstructor;
import lombok.Getter;
import io.swagger.v3.oas.annotations.media.Schema;

@Schema(name = "CurrentUser", description = "当前登录账号")
@Getter
@AllArgsConstructor
public class CurrentUser {
    @Schema(description = "账号 ID")
    private final long id;

    @Schema(description = "登录名")
    private final String username;

    @Schema(description = "角色：USER | ADMIN | SUPER_ADMIN")
    private final String role;

    @Schema(description = "为 true 时只有改密、登出与查询自身可用")
    private final boolean mustChangePassword;
}
