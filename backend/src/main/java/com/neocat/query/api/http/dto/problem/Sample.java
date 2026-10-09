package com.neocat.query.api.http.dto.problem;

import lombok.Getter;
import lombok.Setter;
import io.swagger.v3.oas.annotations.media.Schema;

@Schema(name = "ReportSample", description = "取样行；traceAvailable 表示原始树是否仍可下钻")
@Getter
@Setter
public class Sample {
    @Schema(description = "上报消息 ID")
    private String messageId;

    @Schema(description = "事件时间（epoch millis）")
    private long timestamp;

    @Schema(description = "耗时（毫秒）")
    private long durationMs;

    @Schema(description = "状态：ok | fail 等上报值")
    private String status;

    @Schema(description = "请求摘要")
    private String summary;

    @Schema(description = "原始树是否仍在留存期内")
    private boolean traceAvailable;
}
