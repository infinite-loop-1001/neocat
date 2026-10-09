package com.neocat.query.api.http.dto.metric;

import lombok.Getter;
import lombok.Setter;
import io.swagger.v3.oas.annotations.media.Schema;

@Schema(name = "MetricRankRow", description = "Metric 排名行；other 为跨小时合并项")
@Getter
@Setter
public class MetricRank {
    @Schema(description = "标签组合的展示文本；other 为合并项")
    private String labels;

    @Schema(description = "该组合在小时内的上报次数")
    private long reportCount;

    @Schema(description = "名次，从 1 开始")
    private int rank;
}
