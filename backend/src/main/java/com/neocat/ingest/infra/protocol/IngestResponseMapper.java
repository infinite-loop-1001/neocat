package com.neocat.ingest.infra.protocol;

import com.neocat.common.error.NeocatException;
import com.neocat.common.error.ErrorCode;
import com.neocat.ingest.domain.receive.result.IngestResult;
import com.neocat.ingest.domain.receive.result.IngestStatus;
import com.neocat.protocol.ingest.v1.IngestResponse;
import com.neocat.protocol.ingest.v1.Status;
import java.util.Objects;
import org.springframework.lang.Nullable;

/**
 * 接收结果 → Protobuf 响应（技术方案 04-ingest-protocol.md §5）。
 *
 * <p>状态码映射：
 * <pre>
 * ACCEPTED  → 202（只代表接受了处理尝试，不代表报表已完成）
 * DUPLICATE → 202（幂等命中，客户端视为成功且不重试）
 * DROPPED   → 202（队列满，客户端视为成功且不重试）
 * REJECTED  → 400 / 409 / 422 / 503（按 code 细分）
 * </pre>
 */
public class IngestResponseMapper {

    private IngestResponseMapper() {
    }
    public static IngestResponse toResponse(IngestResult result) {
        return IngestResponse.newBuilder()
                .setStatus(statusOf(result.getStatus()))
                .setCode(wireCode(result.getCode()))
                .setAcceptedTrees(result.getAcceptedTrees())
                .setDuplicateTrees(result.getDuplicateTrees())
                .setDroppedTrees(result.getDroppedTrees())
                .setRejectedTrees(result.getRejectedTrees())
                .setMessage(describe(result))
                .build();
    }
    public static int httpStatusOf(IngestResult result) {
        if (!Objects.equals(result.getStatus(), IngestStatus.REJECTED)) {
            // ACCEPTED / DUPLICATE / DROPPED 都是「平台已受理」语义
            return 202;
        }
        if (Objects.equals("PLATFORM_INITIALIZING", result.getCode())) {
            return 503;
        }
        ErrorCode code = parseCode(result.getCode());
        if (Objects.isNull(code)) return 400;
        return switch (code) {
            case ID_CONFLICT -> 409;
            case TREE_EXPIRED -> 422;
            default -> 400;
        };
    }
    private static Status statusOf(IngestStatus status) {
        return switch (status) {
            case ACCEPTED -> Status.ACCEPTED;
            case DUPLICATE -> Status.DUPLICATE;
            case DROPPED -> Status.DROPPED;
            case REJECTED -> Status.REJECTED;
        };
    }
    private static String describe(IngestResult result) {
        return switch (result.getStatus()) {
            case ACCEPTED -> "已接受处理尝试，报表按分钟刷新";
            case DUPLICATE -> "messageId 已处理过，内容相同，未重复统计";
            case DROPPED -> "队列已满，丢弃监控数据且不补算";
            case REJECTED -> result.getCode();
        };
    }
    @Nullable
    private static ErrorCode parseCode(String code) {
        if (Objects.isNull(code)) return null;
        try {
            return ErrorCode.valueOf(code);
        } catch (IllegalArgumentException ignored) {
            return null;
        }
    }
    private static String wireCode(String code) {
        ErrorCode error = parseCode(code);
        return Objects.isNull(error) ? (Objects.isNull(code) ? "OK" : code) : Integer.toString(error.code());
    }
    /** 复用核心错误到上报响应，便于统一异常处理。 */
    public static IngestResponse fromError(NeocatException error, int trees) {
        return IngestResponse.newBuilder()
                .setStatus(Status.REJECTED)
                .setCode(Integer.toString(error.code().code()))
                .setRejectedTrees(trees)
                .setMessage(error.getMessage())
                .build();
    }
    public static int httpStatusOf(NeocatException error) {
        return switch (error.code()) {
            case UNSUPPORTED_VERSION, MALFORMED_TREE, TREE_TOO_LARGE, BATCH_TOO_LARGE -> 400;
            case ID_CONFLICT -> 409;
            case TREE_EXPIRED -> 422;
            default -> 400;
        };
    }
}
