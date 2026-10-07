package com.neocat.ingest.infra.protocol;

import com.neocat.common.error.exception.IngestException;
import com.neocat.ingest.domain.tree.ExceptionValue;
import com.neocat.ingest.domain.tree.HeartbeatValue;
import com.neocat.ingest.domain.receive.IngestBatch;
import com.neocat.ingest.domain.tree.MessageTree;
import com.neocat.ingest.domain.tree.MetricValue;
import com.neocat.ingest.domain.tree.NodeKind;
import com.neocat.ingest.domain.tree.RawNode;
import com.neocat.ingest.domain.tree.RemoteCallValue;
import com.neocat.protocol.ingest.v1.IngestRequest;
import com.neocat.protocol.ingest.v1.Kind;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import static com.neocat.common.error.ErrorCode.MALFORMED_TREE;
import static com.neocat.common.error.ErrorCode.UNSUPPORTED_VERSION;
import org.apache.commons.collections4.MapUtils;

/**
 * 上报协议适配器：Protobuf 批次 → 领域对象（技术方案 04-ingest-protocol.md §2–3）。
 *
 * <p>只做结构映射，不做业务校验（校验由 {@code TreeValidator} 负责），
 * 但会把**无法映射**的情况（未知 kind、缺失必填）转为 {@link IngestException}，
 * 使接收链路能给出统一的 {@code MALFORMED_TREE}。
 *
 * <p>未知的协议版本在此处即拒绝，避免把未来版本的字段误当当前版本解析。
 */
public class IngestRequestMapper {

    private IngestRequestMapper() {
    }
    public static IngestBatch toBatch(IngestRequest request) {
        if (Objects.isNull(request)) {
            throw new IngestException(MALFORMED_TREE, "请求体为空");
        }
        if (!Objects.equals(IngestBatch.SUPPORTED_VERSION, request.getProtocolVersion())) {
            throw new IngestException(UNSUPPORTED_VERSION, request.getProtocolVersion());
        }
        List<MessageTree> trees = new ArrayList<>(request.getTreesCount());
        for (com.neocat.protocol.ingest.v1.MessageTree tree : request.getTreesList()) {
            trees.add(toTree(tree));
        }
        return new IngestBatch(request.getProtocolVersion(), trees);
    }
    public static MessageTree toTree(com.neocat.protocol.ingest.v1.MessageTree tree) {
        List<RawNode> nodes = new ArrayList<>(tree.getNodesCount());
        for (com.neocat.protocol.ingest.v1.Node node : tree.getNodesList()) {
            nodes.add(toNode(node));
        }
        return new MessageTree(
                blankToNull(tree.getServiceName()),
                blankToNull(tree.getInstanceId()),
                blankToNull(tree.getMessageId()),
                blankToNull(tree.getRootMessageId()),
                blankToNull(tree.getParentMessageId()),
                tree.getTreeTimestamp(),
                nodes);
    }
    public static RawNode toNode(com.neocat.protocol.ingest.v1.Node node) {
        return new RawNode(
                blankToNull(node.getNodeId()),
                toKind(node.getKind()),
                emptyToNull(node.getCategory()),
                emptyToNull(node.getName()),
                node.getStatus(),
                node.getTimestamp(),
                node.getDurationMs(),
                emptyToNull(node.getParentNodeId()),
                node.hasMetric() ? toMetric(node.getMetric()) : null,
                node.hasHeartbeat() ? toHeartbeat(node.getHeartbeat()) : null,
                node.hasRemoteCall() ? toRemoteCall(node.getRemoteCall()) : null,
                node.hasException() ? toException(node.getException()) : null,
                MapUtils.isEmpty(node.getTagsMap()) ? Map.of() : Map.copyOf(node.getTagsMap()));
    }
    private static NodeKind toKind(Kind kind) {
        return switch (kind) {
            case TRANSACTION -> NodeKind.TRANSACTION;
            case EVENT -> NodeKind.EVENT;
            case HEARTBEAT -> NodeKind.HEARTBEAT;
            case METRIC -> NodeKind.METRIC;
            case REMOTE_CALL -> NodeKind.REMOTE_CALL;
            // UNRECOGNIZED 与 KIND_UNSPECIFIED 都视为无法映射
            default -> throw new IngestException(MALFORMED_TREE, "未知的节点类型：" + kind);
        };
    }
    private static MetricValue toMetric(com.neocat.protocol.ingest.v1.MetricValue metric) {
        return new MetricValue(
                blankToNull(metric.getName()),
                metric.getValue(),
                MapUtils.isEmpty(metric.getLabelsMap()) ? Map.of() : Map.copyOf(metric.getLabelsMap()));
    }
    public static HeartbeatValue toHeartbeat(com.neocat.protocol.ingest.v1.Heartbeat hb) {
        Map<String, Long> values = new java.util.LinkedHashMap<>();
        for (var field : hb.getDescriptorForType().getFields()) {
            if (field.getNumber() > 20) continue;
            if (!hb.hasField(field) && (field.getNumber() > 5 || hb.getPresenceAware())) continue;
            long value = (Long) hb.getField(field);
            if (value >= 0) values.put(heartbeatMetric(field.getName()), value);
        }
        return new HeartbeatValue(values);
    }
    private static String heartbeatMetric(String field) {
        if (Objects.equals(field, "thread_count")) return "threads";
        return field.replace("_bytes", "").replace("_ms", "").replace('_', '-');
    }
    private static RemoteCallValue toRemoteCall(com.neocat.protocol.ingest.v1.RemoteCall call) {
        return new RemoteCallValue(
                blankToNull(call.getDownstreamService()),
                emptyToNull(call.getDownstreamAddress()),
                emptyToNull(call.getCallType()),
                call.getStatus());
    }
    private static ExceptionValue toException(com.neocat.protocol.ingest.v1.ExceptionInfo ex) {
        return new ExceptionValue(
                blankToNull(ex.getExceptionName()),
                emptyToNull(ex.getExceptionMessage()),
                emptyToNull(ex.getStackTrace()));
    }
    /** protobuf 的 string 默认为空串；必填字段的空串在此转为 null，交由校验链统一拒绝。 */
    private static String blankToNull(String value) {
        return Objects.isNull(value) || value.isBlank() ? null : value;
    }
    private static String emptyToNull(String value) {
        return Objects.isNull(value) || value.isEmpty() ? null : value;
    }
}
