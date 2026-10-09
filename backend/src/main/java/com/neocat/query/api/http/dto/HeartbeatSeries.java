package com.neocat.query.api.http.dto;

import lombok.Getter;
import lombok.Setter;
import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;

@Schema(name = "HeartbeatSeries", description = "心跳趋势；不合并不同 JVM 的值")
@Getter
@Setter
public class HeartbeatSeries {
    @Schema(description = "服务名")
    private String service;

    @Schema(description = "JVM 指标 key")
    private String metric;

    @Schema(description = "桶粒度（秒）")
    private long bucketSeconds;

    @Schema(description = "按实例分组的序列")
    private List<InstanceSeries> series;

    @Schema(description = "环比序列；一期心跳不支持，恒为 null", nullable = true)
    private Mom mom;
}
