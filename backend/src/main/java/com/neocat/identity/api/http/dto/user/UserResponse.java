package com.neocat.identity.api.http.dto.user;

import lombok.AllArgsConstructor;
import lombok.Getter;
import io.swagger.v3.oas.annotations.media.Schema;

@Schema(name = "UserResponse", description = "账号管理列表中的账号")
@Getter
@AllArgsConstructor
public class UserResponse {
    @Schema(description = "账号 ID")
    private final long id;

    @Schema(description = "登录名")
    private final String username;

    @Schema(description = "角色：USER | ADMIN | SUPER_ADMIN")
    private final String role;

    @Schema(description = "状态：ENABLED | DISABLED")
    private final String status;

    @Schema(description = "为 true 表示下次登录必须先改密")
    private final boolean mustChangePassword;
}
