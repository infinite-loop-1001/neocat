package com.neocat.query.api.http.dto;

import lombok.Getter;
import lombok.Setter;
import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;

@Schema(name = "HeartbeatInstanceSeries", description = "单实例的心跳序列")
@Getter
@Setter
public class InstanceSeries {
    @Schema(description = "实例 ID")
    private String instance;

    @Schema(description = "该实例的趋势点")
    private List<HeartbeatPoint> points;
}
