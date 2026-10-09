package com.neocat.identity.api.http.dto.auth;

import lombok.AllArgsConstructor;
import lombok.Getter;
import io.swagger.v3.oas.annotations.media.Schema;
import com.neocat.identity.api.http.dto.user.UserSummary;

@Schema(name = "LoginResponse", description = "登录响应")
@Getter
@AllArgsConstructor
public class LoginResponse {
    @Schema(description = "账号摘要")
    private final UserSummary user;

    @Schema(description = "为 true 时前端进入强制改密流程")
    private final boolean mustChangePassword;

    @Schema(description = "登录落点")
    private final Entry entry;
}
