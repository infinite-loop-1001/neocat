package com.neocat.identity.api.http.dto;

import lombok.AllArgsConstructor;
import lombok.Getter;
import com.fasterxml.jackson.annotation.JsonCreator;
import io.swagger.v3.oas.annotations.media.Schema;

@Schema(name = "PasswordDraft", description = "重置口令的请求")
@Getter
@AllArgsConstructor(onConstructor_ = @JsonCreator(mode = JsonCreator.Mode.PROPERTIES))
public class PasswordDraft {
    @Schema(description = "新口令；重置后该账号进入强制改密状态",
            requiredMode = Schema.RequiredMode.REQUIRED)
    private final String password;
}
