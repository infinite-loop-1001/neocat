package com.neocat.ingest.domain.validation;

import com.neocat.ingest.config.IngestConfig;

import java.time.Instant;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import org.springframework.modulith.NamedInterface;
import org.springframework.stereotype.Component;

/**
 * 事件时间（迟到）判定（PRD 02 §7）。
 *
 * <p>规则：
 * <ul>
 *   <li>允许接收「当前平台时区自然小时」与「刚结束的上一自然小时」（配置为 2 个小时）；</li>
 *   <li>更早的 MessageTree 整棵拒绝，不进入任何报表/依赖/Trace；</li>
 *   <li>记录数据质量异常，不触发历史回补；</li>
 *   <li>允许范围内的迟到数据可以更新报表数值，但不重新判定已经完成的告警分钟点。</li>
 * </ul>
 *
 * 可接收自然小时数在每次判定时读取动态配置。
 */
@NamedInterface("tree")
@Component
public class LatenessPolicy {

    /** 客户端时钟偏移容差（毫秒）。 */
    static final long FUTURE_TOLERANCE_MS = 60_000L;

    /**
     * @return {@code true} 表示可接收
     */
    public boolean acceptable(long treeTimestamp, Instant now, ZoneId zone) {
        Instant at = Instant.ofEpochMilli(treeTimestamp);
        if (at.isAfter(now.plusMillis(FUTURE_TOLERANCE_MS))) {
            return false;
        }
        return !at.isBefore(windowStart(now, zone));
    }
    /**
     * 可接收窗口的起点（含）。
     *
     * <p>窗口起点 = 当前自然小时起点 − (acceptLateHours − 1) 小时。
     * 例：acceptLateHours=2 且 now 在 12:23 时，起点为 11:00。
     */
    public Instant windowStart(Instant now, ZoneId zone) {
        return now.atZone(zone)
                .truncatedTo(ChronoUnit.HOURS)
                .minusHours(Math.max(1, IngestConfig.ACCEPT_LATE_HOURS) - 1L)
                .toInstant();
    }
}
