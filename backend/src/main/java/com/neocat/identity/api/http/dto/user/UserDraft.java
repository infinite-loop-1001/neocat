package com.neocat.identity.api.http.dto.user;

import lombok.AllArgsConstructor;
import lombok.Getter;
import io.swagger.v3.oas.annotations.media.Schema;

@Schema(name = "UserDraft", description = "管理员创建账号的请求；只能创建 USER 角色")
@Getter
@AllArgsConstructor
public class UserDraft {
    @Schema(description = "登录名，全局唯一", requiredMode = Schema.RequiredMode.REQUIRED)
    private final String username;

    @Schema(description = "初始口令", requiredMode = Schema.RequiredMode.REQUIRED)
    private final String password;
}
