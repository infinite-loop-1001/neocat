package com.neocat.catalog.api.http.dto;

import lombok.AllArgsConstructor;
import lombok.Getter;
import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;

@Schema(name = "ServiceResponse", description = "服务及其有数据的实例列表")
@Getter
@AllArgsConstructor
public class ServiceResponse {
    @Schema(description = "服务名")
    private final String name;

    @Schema(description = "当前报表类型与时间范围内有数据的实例 ID")
    private final List<String> instances;
}
