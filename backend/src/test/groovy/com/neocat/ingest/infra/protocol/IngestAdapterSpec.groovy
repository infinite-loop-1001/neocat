package com.neocat.ingest.infra.protocol

import com.neocat.common.error.NeocatException
import com.neocat.ingest.domain.receive.result.IngestResult
import com.neocat.ingest.domain.receive.result.IngestStatus
import com.neocat.ingest.domain.tree.NodeKind
import com.neocat.protocol.ingest.v1.ExceptionInfo
import com.neocat.protocol.ingest.v1.Heartbeat
import com.neocat.protocol.ingest.v1.IngestRequest
import com.neocat.protocol.ingest.v1.Kind
import com.neocat.protocol.ingest.v1.MessageTree
import com.neocat.protocol.ingest.v1.MetricValue
import com.neocat.protocol.ingest.v1.Node
import com.neocat.protocol.ingest.v1.RemoteCall
import spock.lang.Specification
import spock.lang.Unroll

import static com.neocat.common.error.ErrorCode.*
import com.neocat.common.error.exception.IngestException

/**
 * G12 任务89（红）：上报协议适配。
 * 对应技术方案 04-ingest-protocol.md §2（schema）、§3（语义映射）、
 * §5（响应码）、§6（幂等与冲突）。
 */
class IngestAdapterSpec extends Specification {

    static IngestRequest request(MessageTree... trees) {
        return IngestRequest.newBuilder().setProtocolVersion("1.0").addAllTrees(trees.toList()).build()
    }

    static MessageTree protoTree(Node... nodes) {
        return protoTree("order", "10.0.0.8", "m-1", 1_790_000_000_000L, nodes)
    }

    static MessageTree protoTree(String service, String instance, String messageId,
                                 long timestamp, Node... nodes) {
        return MessageTree.newBuilder()
                .setServiceName(service)
                .setInstanceId(instance)
                .setMessageId(messageId)
                .setRootMessageId(messageId)
                .setTreeTimestamp(timestamp)
                .addAllNodes(nodes.toList())
                .build()
    }

    static Node protoNode(Kind kind = Kind.TRANSACTION, String category = "URL",
                          String name = "POST /orders", String status = "0",
                          long durationMs = 12L, long timestamp = 1_790_000_000_000L) {
        return Node.newBuilder()
                .setNodeId(UUID.randomUUID().toString())
                .setKind(kind)
                .setCategory(category)
                .setName(name)
                .setStatus(status)
                .setTimestamp(timestamp)
                .setDurationMs(durationMs)
                .build()
    }

    // ── 批次与版本 ───────────────────────────────────────────

    def "协议版本为 1.0 时映射成功"() {
        when:
        def batch = IngestRequestMapper.toBatch(request(protoTree(protoNode())))

        then:
        batch.getProtocolVersion() == "1.0"
        batch.getTrees().size() == 1
    }

    @Unroll
    def "不支持的协议版本 '#version' 被拒绝"() {
        when:
        IngestRequestMapper.toBatch(IngestRequest.newBuilder()
                .setProtocolVersion(version)
                .addTrees(protoTree(protoNode()))
                .build())

        then:
        def e = thrown(NeocatException)
        e.code() == UNSUPPORTED_VERSION

        where:
        version << ["2.0", "", "0.9"]
    }

    def "空请求被拒绝"() {
        when:
        IngestRequestMapper.toBatch(null)

        then:
        def e = thrown(NeocatException)
        e.code() == MALFORMED_TREE
    }

    // ── 树字段 ───────────────────────────────────────────────

    def "树级 ID 与时间字段被完整映射"() {
        given:
        def proto = MessageTree.newBuilder()
                .setServiceName("order")
                .setInstanceId("10.0.0.8")
                .setMessageId("m-1")
                .setRootMessageId("root-1")
                .setParentMessageId("parent-1")
                .setTreeTimestamp(1_790_000_000_000L)
                .addNodes(protoNode())
                .build()

        when:
        def tree = IngestRequestMapper.toTree(proto)

        then:
        tree.getServiceName() == "order"
        tree.getInstanceId() == "10.0.0.8"
        tree.getMessageId() == "m-1"
        tree.getRootMessageId() == "root-1"
        tree.getParentMessageId() == "parent-1"
        tree.getTreeTimestamp() == 1_790_000_000_000L
        tree.hasParent()
    }

    def "必填字段为空串时映射为 null，交由校验链拒绝"() {
        given: "protobuf 的 string 默认是空串"
        def proto = MessageTree.newBuilder()
                .setServiceName("")
                .setInstanceId("10.0.0.8")
                .setMessageId("m-1")
                .addNodes(protoNode())
                .build()

        when:
        def tree = IngestRequestMapper.toTree(proto)

        then:
        tree.getServiceName() == null
    }

    def "缺省 rootMessageId 时映射为 null 而非空串"() {
        given:
        def proto = MessageTree.newBuilder()
                .setServiceName("order")
                .setInstanceId("10.0.0.8")
                .setMessageId("m-1")
                .addNodes(protoNode())
                .build()

        when:
        def tree = IngestRequestMapper.toTree(proto)

        then:
        tree.getRootMessageId() == null
        !tree.hasParent()
    }

    // ── 节点映射 ─────────────────────────────────────────────

    @Unroll
    def "kind #protoKind 映射为 #expected"() {
        when:
        def node = IngestRequestMapper.toNode(protoNode(protoKind))

        then:
        node.getKind() == expected

        where:
        protoKind             | expected
        Kind.TRANSACTION      | NodeKind.TRANSACTION
        Kind.EVENT            | NodeKind.EVENT
        Kind.HEARTBEAT        | NodeKind.HEARTBEAT
        Kind.METRIC           | NodeKind.METRIC
        Kind.REMOTE_CALL      | NodeKind.REMOTE_CALL
    }

    def "UNSPECIFIED 与 UNRECOGNIZED 节点被拒绝"() {
        when:
        IngestRequestMapper.toNode(protoNode(Kind.KIND_UNSPECIFIED))

        then:
        def e = thrown(NeocatException)
        e.code() == MALFORMED_TREE
    }

    def "节点基础字段被映射"() {
        given:
        def proto = Node.newBuilder()
                .setNodeId("n-1")
                .setKind(Kind.TRANSACTION)
                .setCategory("SQL")
                .setName("select_order")
                .setStatus("ERROR")
                .setTimestamp(1_790_000_000_000L)
                .setDurationMs(350L)
                .setParentNodeId("n-0")
                .build()

        when:
        def node = IngestRequestMapper.toNode(proto)

        then:
        node.getNodeId() == "n-1"
        node.getCategory() == "SQL"
        node.getName() == "select_order"
        node.getStatus() == "ERROR"
        node.getDurationMs() == 350L
        node.getParentNodeId() == "n-0"
        !node.succeeded()
    }

    def "成功状态严格为字符串 0"() {
        expect:
        IngestRequestMapper.toNode(protoNode(Kind.TRANSACTION, "URL", "/a", "0")).succeeded()
        !IngestRequestMapper.toNode(protoNode(Kind.TRANSACTION, "URL", "/a", "ERROR")).succeeded()
        !IngestRequestMapper.toNode(protoNode(Kind.TRANSACTION, "URL", "/a", "1")).succeeded()
    }

    // ── 载荷映射 ─────────────────────────────────────────────

    def "Metric 载荷被映射（含标签）"() {
        given:
        def proto = Node.newBuilder()
                .setNodeId("n-1")
                .setKind(Kind.METRIC)
                .setCategory("order.amount")
                .setName("order.amount")
                .setStatus("0")
                .setTimestamp(1L)
                .setMetric(MetricValue.newBuilder()
                        .setName("order.amount")
                        .setValue(128.5d)
                        .putLabels("city", "上海")
                        .putLabels("channel", "app")
                        .build())
                .build()

        when:
        def node = IngestRequestMapper.toNode(proto)

        then:
        node.getMetric().getName() == "order.amount"
        node.getMetric().getValue() == 128.5d
        node.getMetric().getLabels() == [city: "上海", channel: "app"]
    }

    def "Heartbeat 载荷五个 JVM 字段被映射"() {
        given:
        def proto = Node.newBuilder()
                .setNodeId("n-1")
                .setKind(Kind.HEARTBEAT)
                .setCategory("jvm")
                .setName("jvm")
                .setStatus("0")
                .setTimestamp(1L)
                .setHeartbeat(Heartbeat.newBuilder()
                        .setHeapUsedBytes(512_000_000L)
                        .setHeapMaxBytes(2_048_000_000L)
                        .setGcCount(12L)
                        .setGcTimeMs(340L)
                        .setThreadCount(96L)
                        .build())
                .build()

        when:
        def node = IngestRequestMapper.toNode(proto)

        then:
        def hb = node.getHeartbeat()
        hb.heapUsedBytes() == 512_000_000L
        hb.heapMaxBytes() == 2_048_000_000L
        hb.gcCount() == 12L
        hb.gcTimeMs() == 340L
        hb.threadCount() == 96L
    }

    def "RemoteCall 载荷被映射（下游服务缺失时映射为 null）"() {
        given:
        def withDownstream = Node.newBuilder()
                .setNodeId("n-1").setKind(Kind.REMOTE_CALL).setCategory("RPC").setName("pay")
                .setStatus("0").setTimestamp(1L).setDurationMs(45L)
                .setRemoteCall(RemoteCall.newBuilder()
                        .setDownstreamService("pay")
                        .setCallType("RPC")
                        .setStatus("0")
                        .build())
                .build()

        when:
        def node = IngestRequestMapper.toNode(withDownstream)

        then:
        node.getRemoteCall().getDownstreamService() == "pay"
        node.getRemoteCall().getCallType() == "RPC"
    }

    def "RemoteCall 缺下游服务名时映射为 null"() {
        given:
        def proto = Node.newBuilder()
                .setNodeId("n-1").setKind(Kind.REMOTE_CALL).setCategory("RPC").setName("unknown")
                .setStatus("0").setTimestamp(1L)
                .setRemoteCall(RemoteCall.newBuilder().setCallType("RPC").build())
                .build()

        when:
        def node = IngestRequestMapper.toNode(proto)

        then:
        node.getRemoteCall().getDownstreamService() == null
    }

    def "异常载荷被映射：异常名是 Problem 聚合键"() {
        given:
        def proto = Node.newBuilder()
                .setNodeId("n-1").setKind(Kind.TRANSACTION).setCategory("SQL").setName("select_order")
                .setStatus("ERROR").setTimestamp(1L).setDurationMs(5000L)
                .setException(ExceptionInfo.newBuilder()
                        .setExceptionName("java.sql.SQLTimeoutException")
                        .setExceptionMessage("timeout")
                        .setStackTrace("at ...")
                        .build())
                .build()

        when:
        def node = IngestRequestMapper.toNode(proto)

        then:
        node.getException().getExceptionName() == "java.sql.SQLTimeoutException"
        node.getException().getExceptionMessage() == "timeout"
    }

    def "未设置载荷时映射为 null，避免伪造空载荷"() {
        when:
        def node = IngestRequestMapper.toNode(protoNode(Kind.TRANSACTION))

        then:
        node.getMetric() == null
        node.getHeartbeat() == null
        node.getRemoteCall() == null
        node.getException() == null
        node.getTags().isEmpty()
    }

    def "标签被映射"() {
        given:
        def proto = Node.newBuilder()
                .setNodeId("n-1").setKind(Kind.TRANSACTION).setCategory("URL").setName("/a")
                .setStatus("0").setTimestamp(1L)
                .putTags("env", "prod")
                .build()

        when:
        def node = IngestRequestMapper.toNode(proto)

        then:
        node.getTags() == [env: "prod"]
    }

    // ── 响应映射（PRD 02 §11） ───────────────────────────────

    @Unroll
    def "接收结果 #status 映射为 HTTP #expectedHttp"() {
        given:
        def result = switch (status) {
            case IngestStatus.ACCEPTED -> IngestResult.accepted(2)
            case IngestStatus.DUPLICATE -> IngestResult.duplicate(1)
            case IngestStatus.DROPPED -> IngestResult.dropped(3)
            default -> IngestResult.rejected("MALFORMED_TREE", 1)
        }

        when:
        def httpStatus = IngestResponseMapper.httpStatusOf(result)
        def response = IngestResponseMapper.toResponse(result)

        then:
        httpStatus == expectedHttp
        response.code == expectedCode

        where:
        status                   | expectedHttp | expectedCode
        IngestStatus.ACCEPTED    | 202          | "OK"
        IngestStatus.DUPLICATE   | 202          | "DUPLICATE"
        IngestStatus.DROPPED     | 202          | "QUEUE_FULL"
        IngestStatus.REJECTED    | 400          | Integer.toString(MALFORMED_TREE.code())
    }

    def "DUPLICATE 与 DROPPED 都返回 202：客户端视为成功且不重试"() {
        expect: "PRD 02 §11：丢弃不补算，客户端不应重试"
        IngestResponseMapper.httpStatusOf(IngestResult.duplicate(1)) == 202
        IngestResponseMapper.httpStatusOf(IngestResult.dropped(1)) == 202
    }

    def "ID 冲突返回 409：同 ID 不同内容不应重试"() {
        expect:
        IngestResponseMapper.httpStatusOf(IngestResult.rejected("ID_CONFLICT", 1)) == 409
    }

    def "过期树返回 422：整棵拒绝且不重试"() {
        expect:
        IngestResponseMapper.httpStatusOf(IngestResult.rejected("TREE_EXPIRED", 1)) == 422
    }

    def "响应体包含各计数，便于客户端区分处理结果"() {
        when:
        def result = new IngestResult(IngestStatus.ACCEPTED, "OK", 5, 2, 1, 3)
        def response = IngestResponseMapper.toResponse(result)

        then:
        response.acceptedTrees == 5
        response.duplicateTrees == 2
        response.droppedTrees == 1
        response.rejectedTrees == 3
    }

    def "ACCEPTED 的语义说明明确表示报表尚未完成"() {
        when:
        def response = IngestResponseMapper.toResponse(IngestResult.accepted(1))

        then: "PRD 02 §4：接收成功不表示报表已完成"
        response.message.contains("报表按分钟刷新")
    }

    def "异常到响应的映射保留错误码"() {
        when:
        def response = IngestResponseMapper.fromError(
                 new IngestException(BATCH_TOO_LARGE), 300)

        then:
        response.code == Integer.toString(BATCH_TOO_LARGE.code())
        response.rejectedTrees == 300
        IngestResponseMapper.httpStatusOf(new IngestException(BATCH_TOO_LARGE)) == 400
    }
}
