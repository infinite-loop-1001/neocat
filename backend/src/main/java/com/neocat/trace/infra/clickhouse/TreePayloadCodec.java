package com.neocat.trace.infra.clickhouse;

import com.neocat.trace.domain.tree.TraceNode;

import java.util.List;

/**
 * 树内容编解码（技术方案 06 §5：{@code payload} 列）。
 *
 * <p>实现可用 JSON 或 Protobuf；选择 Protobuf 可复用上报协议中的节点结构，
 * 避免为存储再定义一套模型。
 */
public interface TreePayloadCodec {

    String encode(List<TraceNode> nodes);

    List<TraceNode> decode(String payload);
}
