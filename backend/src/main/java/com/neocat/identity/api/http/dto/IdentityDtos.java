package com.neocat.identity.api.http.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
import lombok.Getter;
import com.fasterxml.jackson.annotation.JsonCreator;
import io.swagger.v3.oas.annotations.media.Schema;

/** 身份 HTTP 契约；不暴露口令哈希或会话 ID。 */
@Schema(name = "IdentityDtos", description = "身份与会话请求、响应契约容器")
public final class IdentityDtos {
    private IdentityDtos() {
    }

    @Schema(name = "LoginRequest", description = "登录请求")
    @Getter
    @AllArgsConstructor
    public static class LoginRequest {
        @Schema(description = "登录名", requiredMode = Schema.RequiredMode.REQUIRED)
        private final String username;

        @Schema(description = "口令（明文只在传输层出现，服务端只存哈希）",
                requiredMode = Schema.RequiredMode.REQUIRED)
        private final String password;
    }

    @Schema(name = "ChangePasswordRequest", description = "修改当前账号口令的请求")
    @Getter
    @AllArgsConstructor
    public static class ChangePasswordRequest {
        @Schema(description = "当前口令", requiredMode = Schema.RequiredMode.REQUIRED)
        private final String oldPassword;

        @Schema(description = "新口令，长度不足时返回 400 PASSWORD_TOO_SHORT",
                requiredMode = Schema.RequiredMode.REQUIRED)
        private final String newPassword;
    }

    @Schema(name = "UserDraft", description = "管理员创建账号的请求；只能创建 USER 角色")
    @Getter
    @AllArgsConstructor
    public static class UserDraft {
        @Schema(description = "登录名，全局唯一", requiredMode = Schema.RequiredMode.REQUIRED)
        private final String username;

        @Schema(description = "初始口令", requiredMode = Schema.RequiredMode.REQUIRED)
        private final String password;
    }

    @Schema(name = "PasswordDraft", description = "重置口令的请求")
    @Getter
    @AllArgsConstructor(onConstructor_ = @JsonCreator(mode = JsonCreator.Mode.PROPERTIES))
    public static class PasswordDraft {
        @Schema(description = "新口令；重置后该账号进入强制改密状态",
                requiredMode = Schema.RequiredMode.REQUIRED)
        private final String password;
    }

    @Schema(name = "RoleDraft", description = "变更角色请求")
    @Getter
    @AllArgsConstructor(onConstructor_ = @JsonCreator(mode = JsonCreator.Mode.PROPERTIES))
    public static class RoleDraft {
        @Schema(description = "目标角色，只接受 ADMIN 或 USER", allowableValues = {"ADMIN", "USER"},
                requiredMode = Schema.RequiredMode.REQUIRED)
        private final String role;
    }

    @Schema(name = "UserSummary", description = "登录响应中的账号摘要")
    @Getter
    @AllArgsConstructor
    public static class UserSummary {
        @Schema(description = "账号 ID")
        private final long id;

        @Schema(description = "登录名")
        private final String username;

        @Schema(description = "角色：USER | ADMIN | SUPER_ADMIN")
        private final String role;
    }

    @Schema(name = "CurrentUser", description = "当前登录账号")
    @Getter
    @AllArgsConstructor
    public static class CurrentUser {
        @Schema(description = "账号 ID")
        private final long id;

        @Schema(description = "登录名")
        private final String username;

        @Schema(description = "角色：USER | ADMIN | SUPER_ADMIN")
        private final String role;

        @Schema(description = "为 true 时只有改密、登出与查询自身可用")
        private final boolean mustChangePassword;
    }

    @Schema(name = "UserResponse", description = "账号管理列表中的账号")
    @Getter
    @AllArgsConstructor
    public static class UserResponse {
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

    @Schema(name = "LoginEntry", description = "登录成功后的落点；无数据时只给出服务列表类型")
    @Getter
    @AllArgsConstructor
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class Entry {
        @Schema(description = "落点类型：SERVICE_TRANSACTION | SERVICE_LIST")
        private final String type;

        @Schema(description = "有数据时给出的服务名；SERVICE_LIST 时为 null", nullable = true)
        private final String service;

        @Schema(description = "有数据时给出的报表类型；SERVICE_LIST 时为 null", nullable = true)
        private final String kind;
    }

    @Schema(name = "LoginResponse", description = "登录响应")
    @Getter
    @AllArgsConstructor
    public static class LoginResponse {
        @Schema(description = "账号摘要")
        private final UserSummary user;

        @Schema(description = "为 true 时前端进入强制改密流程")
        private final boolean mustChangePassword;

        @Schema(description = "登录落点")
        private final Entry entry;
    }

    @Schema(name = "IdentitySuccess", description = "无数据体的成功响应")
    @Getter
    @AllArgsConstructor
    public static class Success {
        @Schema(description = "固定为 true")
        private final boolean ok;
    }
}
