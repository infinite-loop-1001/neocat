package com.neocat.query.domain.series;

import com.neocat.common.time.bucket.Bucket;

import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;

/**
 * 环比对齐（PRD 03 §6）。
 *
 * <p>实现方式：**按平台时区做整日偏移**，逐桶平移，而不是按自然月/自然周重算边界。
 * 这样天然满足两条要求：
 * <ul>
 *   <li>「对比按平台时区整日偏移，月环比不是上一个自然月」——
 *       30 天前就是 30×24 小时前；</li>
 *   <li>「按桶序号对齐」—— 当前 {@code 10:20–10:30} 平移后仍是 {@code 10:20–10:30}，
 *       不会因为落在一小时的不同位置而错位。</li>
 * </ul>
 *
 * <p>用 {@link ZoneId} 参数保证偏移在平台时区语义下进行（虽然对固定小时数偏移而言
 * 结果与 UTC 相同，但接口上显式携带时区，避免调用方误以为可以用本地时区），
 * 同时桶边界输出仍是同一 Instant 序列。
 */
@org.springframework.modulith.NamedInterface("query")
@org.springframework.stereotype.Component
public class MomAligner {

    /**
     * 生成当前桶序列对应的对比桶序列（长度与顺序一致）。
     */
    public List<Bucket> shift(List<Bucket> current, MomKind kind, ZoneId zone) {
        long offsetSeconds = (long) kind.daysOffset() * 86400L;
        List<Bucket> shifted = new ArrayList<>(current.size());
        for (Bucket bucket : current) {
            Instant start = bucket.getStart().minusSeconds(offsetSeconds);
            Instant end = bucket.getEnd().minusSeconds(offsetSeconds);
            shifted.add(new Bucket(start, end, bucket.isPartial(), bucket.getCoveredSeconds()));
        }
        return shifted;
    }
    /**
     * 判断某报表类型是否支持环比。
     *
     * <p>PRD 03 §6：Transaction、Event、Problem、Metric 支持；
     * PRD 03 §10：Heartbeat 一期不做环比。
     * Dependency 未在 PRD 中列为支持项，一期不提供。
     */
    public boolean supported(String kind) {
        if (kind == null) {
            return false;
        }
        return switch (kind.toUpperCase(java.util.Locale.ROOT)) {
            case "TRANSACTION", "EVENT", "PROBLEM", "METRIC" -> true;
            default -> false;
        };
    }
}
