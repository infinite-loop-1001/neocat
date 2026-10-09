package com.neocat.query.api.http.dto.metric;

import lombok.Getter;
import lombok.Setter;
import io.swagger.v3.oas.annotations.media.Schema;

@Schema(name = "MetricName", description = "指标名")
@Getter
@Setter
public class MetricName {
    @Schema(description = "指标名")
    private String name;
}
