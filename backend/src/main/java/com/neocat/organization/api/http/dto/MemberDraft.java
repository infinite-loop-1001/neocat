package com.neocat.organization.api.http.dto;

import lombok.AllArgsConstructor;
import lombok.Getter;
import io.swagger.v3.oas.annotations.media.Schema;
import com.fasterxml.jackson.annotation.JsonCreator;

@Schema(name = "MemberDraft", description = "组织成员草稿")
@Getter
@AllArgsConstructor(onConstructor_ = @JsonCreator(mode = JsonCreator.Mode.PROPERTIES))
public class MemberDraft {
    @Schema(description = "账号 ID", requiredMode = Schema.RequiredMode.REQUIRED)
    private final long userId;
}
