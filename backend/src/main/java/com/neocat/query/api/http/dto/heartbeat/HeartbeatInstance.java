package com.neocat.query.api.http.dto.heartbeat;

import java.math.BigDecimal;

import lombok.Getter;
import lombok.Setter;
import io.swagger.v3.oas.annotations.media.Schema;

@Schema(name = "HeartbeatInstanceValue", description = "心跳实例及其最后值")
@Getter
@Setter
public class HeartbeatInstance {
    @Schema(description = "实例 ID")
    private String instance;

    @Schema(description = "窗口内最后一次有效采样值；无采样为 null", nullable = true)
    private BigDecimal value;
}
