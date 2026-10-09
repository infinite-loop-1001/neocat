package com.neocat.query.api.http.dto;

import lombok.Getter;
import lombok.Setter;
import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;

@Schema(name = "MetricLabel", description = "指标标签键及其候选值")
@Getter
@Setter
public class MetricLabel {
    @Schema(description = "标签键")
    private String key;

    @Schema(description = "该键在范围内出现过的取值")
    private List<String> values;
}
