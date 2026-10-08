package com.neocat.trace.api.http;

import com.neocat.common.time.clock.TimeProvider;

import com.neocat.trace.api.http.dto.TraceDtos.TraceResponse;
import com.neocat.trace.api.http.convert.TraceConvert;

import com.neocat.common.error.ErrorCode;
import com.neocat.common.error.exception.ExpiredException;
import com.neocat.common.error.exception.ResourceNotFoundException;
import com.neocat.trace.domain.tree.TraceAssembler;
import com.neocat.trace.config.TraceConfig;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Duration;

import org.springframework.context.annotation.DependsOn;

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
@RestController
@DependsOn("traceConfig")
@RequestMapping("/api/traces")
public class TraceController {

    private final TraceAssembler assembler;


    public TraceController(TraceAssembler assembler) {
        this.assembler = assembler;
    }
    @GetMapping("/{messageId}")
    public ResponseEntity<TraceResponse> trace(@PathVariable String messageId) {
        TraceAssembler.AssemblyResult result = assembler.assemble(
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
