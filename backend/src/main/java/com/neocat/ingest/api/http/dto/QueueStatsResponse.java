package com.neocat.ingest.api.http.dto;

import lombok.AllArgsConstructor;
import lombok.Getter;
import io.swagger.v3.oas.annotations.media.Schema;

@Schema(name = "QueueStatsResponse", description = "上报队列观测数据，用于容量诊断与降级决策")
@Getter
@AllArgsConstructor
public class QueueStatsResponse {
    @Schema(description = "队列容量（条）")
    private final int capacity;

    @Schema(description = "当前队列长度（条）")
    private final int size;

    @Schema(description = "进程启动以来累计丢弃条数")
    private final long droppedTotal;

    @Schema(description = "水位比例：size / capacity，取值 0–1")
    private final double watermark;
}
