package com.neocat.trace.api.http;

import com.neocat.common.time.clock.TimeProvider;

import com.neocat.trace.api.http.dto.TraceResponse;
import com.neocat.trace.api.http.convert.TraceConvert;

import com.neocat.common.error.ErrorCode;
import com.neocat.common.error.exception.ExpiredException;
import com.neocat.common.error.exception.ResourceNotFoundException;
import com.neocat.trace.domain.tree.TraceAssembler;
import com.neocat.trace.config.TraceConfig;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Duration;

import org.springframework.context.annotation.DependsOn;
import com.neocat.trace.domain.tree.result.AssemblyResult;

/**
 * Trace 接口（技术方案 03-api-contract.md §4.9、02 §7）。
 *
 * <p>关键语义：
 * <ul>
 *   <li>{@code MISSING} 与 {@code EXPIRED} 在响应中**分开表达**，原因字符串不同；</li>
 *   <li>原始树超过留存期返回 410 {@code TRACE_EXPIRED}，前端据此禁用下钻；</li>
 *   <li>组装失败不影响已经完成的报表统计（本接口与报表接口互不依赖）。</li>
 * </ul>
 */
@Tag(name = "Trace", description = "按 messageId 组装调用链原始树")
@RestController
@DependsOn("traceConfig")
@RequestMapping("/api/traces")
public class TraceController {

    private final TraceAssembler assembler;

    public TraceController(TraceAssembler assembler) {
        this.assembler = assembler;
    }
    @Operation(operationId = "getTrace", summary = "组装单条消息的调用链",
            description = "MISSING 与 EXPIRED 分开表达：超过留存期返回 410 TRACE_EXPIRED，"
                    + "前端据此禁用下钻；组装失败不影响已完成的报表统计。")
    @ApiResponse(responseCode = "200", description = "调用链树，含缺失与过期节点计数")
    @ApiResponse(responseCode = "404", description = "消息不存在：TRACE_NOT_FOUND")
    @ApiResponse(responseCode = "410", description = "原始树超过留存期：TRACE_EXPIRED")
    @GetMapping("/{messageId}")
    public ResponseEntity<TraceResponse> trace(
            @Parameter(description = "上报消息 ID", required = true) @PathVariable String messageId) {
        AssemblyResult result = assembler.assemble(
                messageId, TimeProvider.now(), Duration.ofDays(TraceConfig.RETENTION_DAYS));

        if (result.isExpired()) {
            throw new ExpiredException(ErrorCode.TRACE_EXPIRED);
        }
        if (result.isMissing()) {
            throw new ResourceNotFoundException(ErrorCode.TRACE_NOT_FOUND);
        }

        return ResponseEntity.ok(TraceConvert.response(messageId, result));
    }
}
