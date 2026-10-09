package com.neocat.query.api.http.dto.metric;

import lombok.Getter;
import lombok.Setter;
import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;

@Schema(name = "MetricCount", description = "单指标上报次数曲线")
@Getter
@Setter
public class MetricCount {
    @Schema(description = "指标名")
    private String metric;

    @Schema(description = "桶粒度（秒）")
    private long bucketSeconds;

    @Schema(description = "count 点；确认无匹配为 0，无法还原合并项为 null")
    private List<CountPoint> points;
}
