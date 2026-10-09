package com.neocat.trace.infra.clickhouse;

import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import com.neocat.trace.domain.tree.TraceNode;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.LinkedHashMap;
import org.springframework.stereotype.Component;

/**
 * 树内容的 JSON 编解码（技术方案 06 §5：{@code payload} 列）。
 *
 * <p>选 JSON 而非 Protobuf 的理由：{@code payload} 只被平台自身读写，
 * 不参与跨语言传输，而 JSON 便于人工排查「某棵树当时上报了什么」——
 * 这在定位「为什么这条记录的缺失节点是它」时很关键。
 *
 * <p>解码失败返回空节点列表而非抛异常：单棵树的内容损坏
 * 不应让整个 Trace 组装失败（PRD 02 §10）。
 */
@Component
public class JsonTreePayloadCodec implements TreePayloadCodec {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final TypeReference<List<Map<String, Object>>> TYPE = new TypeReference<>() {
    };

    @Override
    public String encode(List<TraceNode> nodes) {
        try {
            List<Map<String, Object>> serializable = nodes.stream()
                    .map(JsonTreePayloadCodec::toMap)
                    .toList();
            return MAPPER.writeValueAsString(serializable);
        } catch (Exception e) {
            // 编码失败时存空数组：宁可丢树内明细，也不能让接收链路失败
            return "[]";
        }
    }
    @Override
    public List<TraceNode> decode(String payload) {
        if (Objects.isNull(payload) || payload.isBlank()) {
            return Lists.newArrayList();
        }
        try {
            return MAPPER.readValue(payload, TYPE).stream()
                    .map(JsonTreePayloadCodec::fromMap)
                    .toList();
        } catch (Exception e) {
            return Lists.newArrayList();
        }
    }

    // ── 转换 ─────────────────────────────────────────────────

    private static Map<String, Object> toMap(TraceNode node) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("nodeId", node.getNodeId());
        map.put("kind", node.getKind());
        map.put("category", node.getCategory());
        map.put("name", node.getName());
        map.put("status", node.getStatus());
        map.put("timestamp", node.getTimestamp());
        map.put("durationMs", node.getDurationMs());
        map.put("detail", node.getDetail());
        map.put("tags", node.getTags());
        return map;
    }
    @SuppressWarnings("unchecked")
    private static TraceNode fromMap(Map<String, Object> map) {
        return new TraceNode(
                str(map.get("nodeId")),
                str(map.get("kind")),
                str(map.get("category")),
                str(map.get("name")),
                str(map.get("status")),
                num(map.get("timestamp")),
                num(map.get("durationMs")),
                str(map.get("detail")),
                map.get("tags") instanceof Map<?, ?> tags
                        ? (Map<String, String>) tags
                        : Maps.newHashMap());
    }
    private static String str(Object value) {
        return Objects.isNull(value) ? "" : String.valueOf(value);
    }
    private static long num(Object value) {
        return value instanceof Number number ? number.longValue() : 0L;
    }
}
