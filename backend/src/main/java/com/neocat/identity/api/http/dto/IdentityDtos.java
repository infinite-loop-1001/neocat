package com.neocat.identity.api.http.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
import lombok.Getter;
import com.fasterxml.jackson.annotation.JsonCreator;

/** 身份 HTTP 契约；不暴露口令哈希或会话 ID。 */
public final class IdentityDtos {
    private IdentityDtos() {
    }

    @Getter
    @AllArgsConstructor
    public static class LoginRequest {
        private final String username;

        private final String password;
    }

    @Getter
    @AllArgsConstructor
    public static class ChangePasswordRequest {
        private final String oldPassword;

        private final String newPassword;
    }

    @Getter
    @AllArgsConstructor
    public static class UserDraft {
        private final String username;

        private final String password;
    }

    @Getter
    @AllArgsConstructor(onConstructor_ = @JsonCreator(mode = JsonCreator.Mode.PROPERTIES))
    public static class PasswordDraft {
        private final String password;
    }

    @Getter
    @AllArgsConstructor(onConstructor_ = @JsonCreator(mode = JsonCreator.Mode.PROPERTIES))
    public static class RoleDraft {
        private final String role;
    }

    @Getter
    @AllArgsConstructor
    public static class UserSummary {
        private final long id;

        private final String username;

        private final String role;
    }

    @Getter
    @AllArgsConstructor
    public static class CurrentUser {
        private final long id;

        private final String username;

        private final String role;

        private final boolean mustChangePassword;
    }

    @Getter
    @AllArgsConstructor
    public static class UserResponse {
        private final long id;

        private final String username;

        private final String role;

        private final String status;

        private final boolean mustChangePassword;
    }

    @Getter
    @AllArgsConstructor
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class Entry {
        private final String type;

        private final String service;

        private final String kind;
    }

    @Getter
    @AllArgsConstructor
    public static class LoginResponse {
        private final UserSummary user;

        private final boolean mustChangePassword;

        private final Entry entry;
    }

    @Getter
    @AllArgsConstructor
    public static class Success {
        private final boolean ok;
    }
}
