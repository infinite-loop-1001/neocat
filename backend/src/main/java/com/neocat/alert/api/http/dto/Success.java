package com.neocat.alert.api.http.dto;

import lombok.AllArgsConstructor;
import lombok.Getter;
import io.swagger.v3.oas.annotations.media.Schema;

@Schema(name = "AlertSuccess", description = "无数据体的成功响应")
@Getter
@AllArgsConstructor
public class Success {
    @Schema(description = "固定为 true")
    private final boolean ok;
}
