package com.neocat.ingest.domain.validation

import spock.lang.Specification
import spock.lang.Unroll
import com.neocat.common.config.IngestConfig

import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.temporal.ChronoUnit

/**
 * G5 任务23（红）：上报迟到判定。
 * 对应 PRD 02 §7：只接收当前自然小时与刚结束的上一自然小时；更早整棵拒绝；不补算。
 *
 * <p>约定：{@code acceptLateHours} 表示「可接收的自然小时个数」。
 * 取 2 时窗口为 [当前小时起点 − 1h, now]；取 1 时只有当前小时。
 */
class IngestLatenessSpec extends Specification {

    static final ZoneId SH = ZoneId.of("Asia/Shanghai")

    // 平台时区 2026-09-24 12:23:41
    static final Instant NOW = ZonedDateTime.of(2026, 9, 24, 12, 23, 41, 0, SH).toInstant()
    static final Instant CURRENT_HOUR_START = ZonedDateTime.of(2026, 9, 24, 12, 0, 0, 0, SH).toInstant()
    static final Instant PREV_HOUR_START = ZonedDateTime.of(2026, 9, 24, 11, 0, 0, 0, SH).toInstant()
    static final Instant TWO_HOURS_AGO = ZonedDateTime.of(2026, 9, 24, 10, 0, 0, 0, SH).toInstant()

    LatenessPolicy policy = new LatenessPolicy()

    def "当前自然小时内的数据可接收"() {
        expect:
        policy.acceptable(CURRENT_HOUR_START.toEpochMilli(), NOW, SH)
        policy.acceptable(NOW.toEpochMilli(), NOW, SH)
    }

    def "刚结束的上一自然小时的数据可接收"() {
        expect:
        policy.acceptable(PREV_HOUR_START.toEpochMilli(), NOW, SH)
        policy.acceptable(PREV_HOUR_START.plusSeconds(1800).toEpochMilli(), NOW, SH)
        policy.acceptable(CURRENT_HOUR_START.minusMillis(1).toEpochMilli(), NOW, SH)
    }

    def "更早的数据整棵拒绝（上上小时及以前）"() {
        expect:
        !policy.acceptable(TWO_HOURS_AGO.toEpochMilli(), NOW, SH)
        !policy.acceptable(TWO_HOURS_AGO.minusSeconds(86400).toEpochMilli(), NOW, SH)
    }

    @Unroll
    def "acceptLateHours=#lateHours 时窗口起点为当前小时起点往前 #offset 小时"() {
        given:
        IngestConfig.ACCEPT_LATE_HOURS = lateHours
        def custom = new LatenessPolicy()

        expect:
        custom.windowStart(NOW, SH) == ZonedDateTime.of(2026, 9, 24, 12, 0, 0, 0, SH)
                .minus(offset, ChronoUnit.HOURS).toInstant()

        where:
        lateHours | offset
        2         | 1
        1         | 0
    }

    def "acceptLateHours=1 时上一小时被拒，只有当前小时可接收"() {
        given:
        IngestConfig.ACCEPT_LATE_HOURS = 1
        def onlyCurrent = new LatenessPolicy()

        expect:
        onlyCurrent.acceptable(CURRENT_HOUR_START.toEpochMilli(), NOW, SH)
        !onlyCurrent.acceptable(PREV_HOUR_START.toEpochMilli(), NOW, SH)
        !onlyCurrent.acceptable(CURRENT_HOUR_START.minusMillis(1).toEpochMilli(), NOW, SH)
    }

    def "未来时间超过容差被拒"() {
        expect:
        !policy.acceptable(NOW.plusSeconds(120).toEpochMilli(), NOW, SH)
    }

    def "未来时间在容差内可接收（容忍客户端时钟轻微偏移）"() {
        expect:
        policy.acceptable(NOW.plusSeconds(30).toEpochMilli(), NOW, SH)
    }

    def "跨自然小时边界：11:59:59 的数据在 12:23 仍可接收"() {
        given:
        def at1159 = ZonedDateTime.of(2026, 9, 24, 11, 59, 59, 0, SH).toInstant()

        expect:
        policy.acceptable(at1159.toEpochMilli(), NOW, SH)
    }

    def "跨自然小时边界：10:59:59 的数据在 12:23 被拒"() {
        given:
        def at1059 = ZonedDateTime.of(2026, 9, 24, 10, 59, 59, 0, SH).toInstant()

        expect:
        !policy.acceptable(at1059.toEpochMilli(), NOW, SH)
    }

    def "迟到判定使用平台时区而非 UTC 自然小时"() {
        given: "平台时区 10:30 的数据（= 02:30 UTC）"
        def at1030Sh = ZonedDateTime.of(2026, 9, 24, 10, 30, 0, 0, SH).toInstant()

        expect: "在平台时区为上海、now=12:23 时，属于上上小时，整棵拒绝"
        !policy.acceptable(at1030Sh.toEpochMilli(), NOW, SH)

        and: "若平台时区为 UTC 且 now=02:40 UTC，同一时刻属于当前小时，可接收"
        policy.acceptable(at1030Sh.toEpochMilli(),
                ZonedDateTime.of(2026, 9, 24, 2, 40, 0, 0, ZoneId.of("UTC")).toInstant(),
                ZoneId.of("UTC"))
    }
}
