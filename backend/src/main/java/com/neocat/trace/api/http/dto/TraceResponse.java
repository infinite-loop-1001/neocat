package com.neocat.trace.api.http.dto;

import lombok.AllArgsConstructor;
import lombok.Getter;
import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;

@Schema(name = "TraceTree", description = "单条消息的调用链树")
@Getter
@AllArgsConstructor
public class TraceResponse {
    @Schema(description = "上报消息 ID")
    private final String messageId;

    @Schema(description = "原始树是否已超过留存期")
    private final boolean expired;

    @Schema(description = "根节点的子节点列表")
    private final List<Node> children;

    @Schema(description = "未落库或上报缺失的节点数")
    private final long missingNodes;

    @Schema(description = "因超过留存期无法组装的节点数")
    private final long expiredNodes;
}
