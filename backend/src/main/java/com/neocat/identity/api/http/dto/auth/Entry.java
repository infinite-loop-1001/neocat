package com.neocat.identity.api.http.dto.auth;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
import lombok.Getter;
import io.swagger.v3.oas.annotations.media.Schema;

@Schema(name = "LoginEntry", description = "登录成功后的落点；无数据时只给出服务列表类型")
@Getter
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class Entry {
    @Schema(description = "落点类型：SERVICE_TRANSACTION | SERVICE_LIST")
    private final String type;

    @Schema(description = "有数据时给出的服务名；SERVICE_LIST 时为 null", nullable = true)
    private final String service;

    @Schema(description = "有数据时给出的报表类型；SERVICE_LIST 时为 null", nullable = true)
    private final String kind;
}
