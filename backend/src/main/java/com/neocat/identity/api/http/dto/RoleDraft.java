package com.neocat.identity.api.http.dto;

import lombok.AllArgsConstructor;
import lombok.Getter;
import com.fasterxml.jackson.annotation.JsonCreator;
import io.swagger.v3.oas.annotations.media.Schema;

@Schema(name = "RoleDraft", description = "变更角色请求")
@Getter
@AllArgsConstructor(onConstructor_ = @JsonCreator(mode = JsonCreator.Mode.PROPERTIES))
public class RoleDraft {
    @Schema(description = "目标角色，只接受 ADMIN 或 USER", allowableValues = {"ADMIN", "USER"},
            requiredMode = Schema.RequiredMode.REQUIRED)
    private final String role;
}
