package com.neocat.identity.api.http.dto.user;

import lombok.AllArgsConstructor;
import lombok.Getter;
import io.swagger.v3.oas.annotations.media.Schema;

@Schema(name = "UserSummary", description = "登录响应中的账号摘要")
@Getter
@AllArgsConstructor
public class UserSummary {
    @Schema(description = "账号 ID")
    private final long id;

    @Schema(description = "登录名")
    private final String username;

    @Schema(description = "角色：USER | ADMIN | SUPER_ADMIN")
    private final String role;
}
