package com.neocat.identity.api.http.dto.auth;

import lombok.AllArgsConstructor;
import lombok.Getter;
import io.swagger.v3.oas.annotations.media.Schema;

@Schema(name = "ChangePasswordRequest", description = "修改当前账号口令的请求")
@Getter
@AllArgsConstructor
public class ChangePasswordRequest {
    @Schema(description = "当前口令", requiredMode = Schema.RequiredMode.REQUIRED)
    private final String oldPassword;

    @Schema(description = "新口令，长度不足时返回 400 PASSWORD_TOO_SHORT",
            requiredMode = Schema.RequiredMode.REQUIRED)
    private final String newPassword;
}
