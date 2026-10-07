package com.neocat.ingest.domain.validation;

import com.neocat.ingest.domain.tree.ExceptionValue;
import com.neocat.ingest.domain.tree.HeartbeatValue;
import com.neocat.ingest.domain.tree.MessageTree;
import com.neocat.ingest.domain.tree.MetricValue;
import com.neocat.ingest.domain.tree.RawNode;
import com.neocat.ingest.domain.tree.RemoteCallValue;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.*;

import org.apache.commons.collections4.MapUtils;

/**
 * 树内容指纹计算（PRD 02 §6.1）。
 *
 * <p>规范化规则（与技术方案 04 §6 一致）：
 * <ul>
 *   <li>字段按固定顺序拼接，不依赖对象字段顺序；</li>
 *   <li>节点按树内出现顺序参与（顺序变化视为内容变化，宁可判为冲突也不吞掉漂移）；</li>
 *   <li>{@code tags} / {@code labels} 按键名字典序排列，消除 Map 迭代顺序差异；</li>
 *   <li>{@code messageId} 不参与指纹，使「同内容不同 ID」得到相同指纹；</li>
 *   <li>浮点值用 {@link Double#toString} 的规范形式，避免精度噪声。</li>
 * </ul>
 */
@org.springframework.modulith.NamedInterface("tree")
@org.springframework.stereotype.Component
public class FingerprintCalculator {

    private static final char FIELD_SEP = '\u001F';

    private static final char RECORD_SEP = '\u001E';

    private static final String NULL = "\u0000NULL";

    public String fingerprint(MessageTree tree) {
        StringBuilder sb = new StringBuilder(512);
        sb.append(safe(tree.getServiceName())).append(FIELD_SEP);
        sb.append(safe(tree.getInstanceId())).append(FIELD_SEP);
        sb.append(safe(tree.getParentMessageId())).append(FIELD_SEP);
        sb.append(tree.getTreeTimestamp()).append(RECORD_SEP);

        for (RawNode node : tree.getNodes()) {
            sb.append(fingerprintNode(node)).append(RECORD_SEP);
        }
        return sha256Hex(sb.toString());
    }
    private String fingerprintNode(RawNode node) {
        StringBuilder sb = new StringBuilder(256);
        sb.append(safe(node.getNodeId())).append(FIELD_SEP);
        sb.append(node.getKind()).append(FIELD_SEP);
        sb.append(safe(node.getCategory())).append(FIELD_SEP);
        sb.append(safe(node.getName())).append(FIELD_SEP);
        sb.append(safe(node.getStatus())).append(FIELD_SEP);
        sb.append(node.getTimestamp()).append(FIELD_SEP);
        sb.append(node.getDurationMs()).append(FIELD_SEP);
        sb.append(safe(node.getParentNodeId())).append(FIELD_SEP);
        sb.append(fingerprintMetric(node.getMetric())).append(FIELD_SEP);
        sb.append(fingerprintHeartbeat(node.getHeartbeat())).append(FIELD_SEP);
        sb.append(fingerprintRemoteCall(node.getRemoteCall())).append(FIELD_SEP);
        sb.append(fingerprintException(node.getException())).append(FIELD_SEP);
        sb.append(canonicalMap(node.getTags()));
        return sb.toString();
    }
    private String fingerprintMetric(MetricValue metric) {
        if (Objects.isNull(metric)) {
            return NULL;
        }
        return safe(metric.getName()) + "=" + metric.getValue() + "[" + canonicalMap(metric.getLabels()) + "]";
    }
    private String fingerprintHeartbeat(HeartbeatValue hb) {
        if (Objects.isNull(hb)) {
            return NULL;
        }
        // Preserve the persisted legacy fingerprint when all five original fields are present.
        if (Objects.equals(hb.getValues().keySet(), Set.of("heap-used", "heap-max", "gc-count", "gc-time", "threads"))) {
            return hb.heapUsedBytes() + "," + hb.heapMaxBytes() + "," + hb.gcCount() + "," + hb.gcTimeMs() + "," + hb.threadCount();
        }
        return canonicalMap(hb.getValues().entrySet().stream().collect(java.util.stream.Collectors.toMap(
                java.util.Map.Entry::getKey, e -> Long.toString(e.getValue()), (left, right) -> {
                    throw new IllegalStateException("重复 Heartbeat 指标键");
                })));
    }
    private String fingerprintRemoteCall(RemoteCallValue call) {
        if (Objects.isNull(call)) {
            return NULL;
        }
        return safe(call.getDownstreamService()) + "," + safe(call.getDownstreamAddress())
                + "," + safe(call.getCallType()) + "," + safe(call.getStatus());
    }
    private String fingerprintException(ExceptionValue ex) {
        if (Objects.isNull(ex)) {
            return NULL;
        }
        return safe(ex.getExceptionName()) + "," + safe(ex.getExceptionMessage()) + "," + safe(ex.getStackTrace());
    }
    /** 按键名字典序拼接，消除 Map 迭代顺序差异。 */
    private String canonicalMap(Map<String, String> map) {
        if (MapUtils.isEmpty(map)) {
            return "";
        }
        TreeMap<String, String> sorted = new TreeMap<>(map);
        StringBuilder sb = new StringBuilder();
        sorted.forEach((k, v) -> sb.append(safe(k)).append('=').append(safe(v)).append(';'));
        return sb.toString();
    }
    private String safe(String value) {
        return Objects.isNull(value) ? NULL : value;
    }
    private String sha256Hex(String payload) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(payload.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("JVM 不支持 SHA-256", e);
        }
    }
}
