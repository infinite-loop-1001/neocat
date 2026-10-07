package com.neocat.analysis.domain.metric;

import com.neocat.ingest.domain.tree.RawNode;

import java.time.Instant;
import java.util.Map;
import java.util.TreeMap;
import java.util.Objects;

/**
 * Metric 标签规范化（PRD 04 §2）。
 *
 * <p>规则：将标签键按稳定顺序（字典序）规范化；相同键值组合视为同一序列。
 * 规范化后的字符串是序列身份的一部分，不能在卡片保存后静默改变。
 */
@org.springframework.modulith.NamedInterface("analysis")
public class MetricLabels {

    private MetricLabels() {
    }
    /** 规范化为稳定字符串；null / 空标签得到空串。 */
    public static String canonicalize(Map<String, String> labels) {
        if (Objects.isNull(labels) || labels.isEmpty()) {
            return "";
        }
        TreeMap<String, String> sorted = new TreeMap<>(labels);
        // Keep ordinary legacy identities stable. Unsafe identities use a versioned,
        // delimiter-free encoding that cannot collide with any old key=value; string.
        if (sorted.entrySet().stream().anyMatch(e -> unsafe(e.getKey()) || unsafe(e.getValue()))) {
            StringBuilder encoded = new StringBuilder("v2:");
            sorted.forEach((key, value) -> encoded.append(encode(key)).append('.').append(encode(value)).append(':'));
            return encoded.toString();
        }
        StringBuilder sb = new StringBuilder();
        sorted.forEach((k, v) -> sb.append(k).append('=').append(v).append(';'));
        return sb.toString();
    }
    private static boolean unsafe(String value) {
        return value.contains("%") || value.contains("=") || value.contains(";");
    }
    private static String encode(String value) {
        return java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(value.getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }
    /** 从节点事件时间取得其所在分钟起点（UTC 对齐，时区转换由查询层负责）。 */
    public static Instant minuteStart(long eventTimestamp) {
        return Instant.ofEpochMilli(eventTimestamp).truncatedTo(java.time.temporal.ChronoUnit.MINUTES);
    }
    /**
     * 节点所属的报表分类名。
     * <ul>
     *   <li>Transaction / Event：节点的 {@code category}（URL / SQL / CALL / CACHE / business）；</li>
     *   <li>Metric：指标名。</li>
     * </ul>
     */
    public static String categoryOf(RawNode node) {
        return Objects.isNull(node.getCategory()) ? "" : node.getCategory();
    }
}
