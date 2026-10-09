package com.neocat.platform.api.http.dto.profile;

import lombok.AllArgsConstructor;
import lombok.Getter;
import io.swagger.v3.oas.annotations.media.Schema;

@Schema(name = "SlowThresholdsRequest", description = "慢阈值（毫秒）；只影响后续分析，不回算历史")
@Getter
@AllArgsConstructor
public class SlowThresholdsRequest {
    @Schema(description = "URL 慢阈值（毫秒）")
    private final int url;

    @Schema(description = "SQL 慢阈值（毫秒）")
    private final int sql;

    @Schema(description = "远程调用慢阈值（毫秒）")
    private final int call;

    @Schema(description = "缓存访问慢阈值（毫秒）")
    private final int cache;
}
