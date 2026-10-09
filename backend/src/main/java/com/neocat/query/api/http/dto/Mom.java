package com.neocat.query.api.http.dto;

import lombok.Getter;
import lombok.Setter;
import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;

@Schema(name = "Mom", description = "环比序列；kind 为 DAY | WEEK | MONTH")
@Getter
@Setter
public class Mom {
    @Schema(description = "环比周期：DAY | WEEK | MONTH")
    private String kind;

    @Schema(description = "对齐后的上一周期点")
    private List<MomPoint> points;
}
