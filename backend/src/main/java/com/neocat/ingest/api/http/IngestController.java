package com.neocat.ingest.api.http;

import com.neocat.ingest.api.http.dto.QueueStatsResponse;
import com.neocat.ingest.api.http.convert.IngestConvert;

import com.neocat.common.error.NeocatException;
import com.neocat.common.error.ErrorCode;
import com.neocat.ingest.infra.protocol.IngestRequestMapper;
import com.neocat.ingest.infra.protocol.IngestResponseMapper;
import com.neocat.ingest.domain.receive.IngestBatch;
import com.neocat.ingest.domain.receive.IngestResult;
import com.neocat.ingest.domain.receive.IngestService;
import com.neocat.protocol.ingest.v1.IngestRequest;
import com.neocat.protocol.ingest.v1.IngestResponse;
import com.neocat.protocol.ingest.v1.Status;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import com.google.protobuf.InvalidProtocolBufferException;

/**
 * 上报接收接口（技术方案 04-ingest-protocol.md §1、§5）。
 *
 * <p>端点 {@code POST /api/v1/ingest}，请求与响应均为 Protobuf。
 *
 * <p>关键语义：**接收成功只表示平台接受了处理尝试，不表示报表已完成**；
 * 队列满时返回 202 + DROPPED，客户端视为成功且不重试（PRD 02 §8、§11）。
 */
@Tag(name = "上报接收", description = "Protobuf 上报接收与队列观测")
@RestController
@RequestMapping("/api/v1")
public class IngestController {

    private static final MediaType PROTOBUF = MediaType.parseMediaType("application/x-protobuf");

    private final IngestService ingest;

    public IngestController(IngestService ingest) {
        this.ingest = ingest;
    }
    @Operation(operationId = "ingestBatch", summary = "接收一批上报数据（Protobuf）",
            description = "请求与响应都是 application/x-protobuf 二进制：报文体是 IngestRequest，"
                    + "响应体是 IngestResponse 生成的协议消息。接收成功只表示平台接受了处理尝试，"
                    + "不代表报表已完成；队列满返回 202 + DROPPED，客户端视为成功且不重试。")
    @ApiResponse(responseCode = "202", description = "ACCEPTED / DUPLICATE / DROPPED，响应体为 IngestResponse",
            content = @Content(mediaType = "application/x-protobuf", schema = @Schema(type = "string", format = "binary")))
    @ApiResponse(responseCode = "400", description = "报文体无法解析或协议非法（MALFORMED_TREE / BATCH_TOO_LARGE 等）",
            content = @Content(mediaType = "application/x-protobuf", schema = @Schema(type = "string", format = "binary")))
    @ApiResponse(responseCode = "409", description = "messageId 冲突：ID_CONFLICT",
            content = @Content(mediaType = "application/x-protobuf", schema = @Schema(type = "string", format = "binary")))
    @ApiResponse(responseCode = "422", description = "树已过期：TREE_EXPIRED",
            content = @Content(mediaType = "application/x-protobuf", schema = @Schema(type = "string", format = "binary")))
    @ApiResponse(responseCode = "500", description = "未预期错误，响应体同样为 IngestResponse",
            content = @Content(mediaType = "application/x-protobuf", schema = @Schema(type = "string", format = "binary")))
    @ApiResponse(responseCode = "503", description = "平台初始化中：PLATFORM_INITIALIZING",
            content = @Content(mediaType = "application/x-protobuf", schema = @Schema(type = "string", format = "binary")))
    @PostMapping(value = "/ingest", consumes = "application/x-protobuf", produces = "application/x-protobuf")
    public ResponseEntity<byte[]> ingest(@RequestBody byte[] payload) {
        try {
            IngestRequest request = IngestRequest.parseFrom(payload);
            IngestBatch batch = IngestRequestMapper.toBatch(request);
            IngestResult result = ingest.accept(batch, payload.length);
            return ResponseEntity.status(IngestResponseMapper.httpStatusOf(result))
                    .contentType(PROTOBUF)
                    .body(IngestResponseMapper.toResponse(result).toByteArray());
        } catch (NeocatException e) {
            return ResponseEntity.status(IngestResponseMapper.httpStatusOf(e))
                    .contentType(PROTOBUF)
                    .body(IngestResponseMapper.fromError(e, 0).toByteArray());
        } catch (InvalidProtocolBufferException e) {
            // 仅反序列化失败是格式错误；其他未预期异常不能误报客户端错误
            IngestResponse response = IngestResponse.newBuilder()
                    .setStatus(Status.REJECTED)
                    .setCode(Integer.toString(ErrorCode.MALFORMED_TREE.code()))
                    .setMessage("请求体无法解析为 IngestRequest")
                    .build();
            return ResponseEntity.badRequest().contentType(PROTOBUF).body(response.toByteArray());
        } catch (Exception e) {
            IngestResponse response = IngestResponse.newBuilder()
                    .setStatus(Status.REJECTED)
                    .setCode(Integer.toString(ErrorCode.INTERNAL_ERROR.code()))
                    .setMessage(ErrorCode.INTERNAL_ERROR.message())
                    .build();
            return ResponseEntity.internalServerError().contentType(PROTOBUF).body(response.toByteArray());
        }
    }
    /** 队列观测：容量、水位与累计丢弃，用于容量诊断与降级决策。 */
    @Operation(operationId = "ingestQueueStats", summary = "查询上报队列水位",
            description = "返回队列容量、当前长度、累计丢弃数与水位比例，用于容量诊断与降级决策。")
    @ApiResponse(responseCode = "200", description = "队列观测数据")
    @GetMapping("/ingest/stats")
    public ResponseEntity<QueueStatsResponse> stats() {
        return ResponseEntity.ok(IngestConvert.stats(ingest.queueStats()));
    }
}
