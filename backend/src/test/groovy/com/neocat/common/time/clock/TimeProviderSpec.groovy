package com.neocat.common.time.clock

import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicReference
import spock.lang.Specification

class TimeProviderSpec extends Specification {
    def cleanup() {
        TimeProvider.clock = Clock.systemUTC()
    }

    def "默认 UTC 时间位于系统取时边界内"() {
        given:
        def before = Clock.systemUTC().instant()
        when:
        def actual = TimeProvider.now()
        then:
        !actual.isBefore(before)
        !actual.isAfter(Clock.systemUTC().instant())
    }

    def "单测主动设置时钟，后一次设置直接替换前一次"() {
        given:
        def first = Instant.parse('2026-10-08T01:02:03.456Z')
        def second = first.plusSeconds(60)
        when:
        TimeProvider.clock = Clock.fixed(first, ZoneOffset.UTC)
        then:
        TimeProvider.now() == first
        TimeProvider.millis() == first.toEpochMilli()
        when:
        TimeProvider.clock = Clock.fixed(second, ZoneOffset.UTC)
        then:
        TimeProvider.now() == second
        TimeProvider.millis() == second.toEpochMilli()
        when:
        TimeProvider.clock = Clock.systemUTC()
        then:
        TimeProvider.now() != second
    }

    def "所有线程共用一个时钟，子线程设置也立即全局生效"() {
        given:
        def first = Instant.EPOCH
        def second = first.plusSeconds(60)
        def executor = Executors.newSingleThreadExecutor()
        TimeProvider.clock = Clock.fixed(first, ZoneOffset.UTC)
        expect:
        executor.submit({ TimeProvider.now() } as Callable).get() == first
        executor.submit({ TimeProvider.millis() } as Callable).get() == 0
        when:
        executor.submit({
            TimeProvider.clock = Clock.fixed(second, ZoneOffset.UTC)
            TimeProvider.now()
        } as Callable).get()
        then:
        TimeProvider.now() == second
        cleanup:
        executor.shutdownNow()
    }

    def "可推进时钟的时间与毫秒随同一个静态入口变化"() {
        given:
        def current = new AtomicReference(Instant.EPOCH)
        TimeProvider.clock = new Clock() {
            ZoneId getZone() { ZoneOffset.UTC }
            Clock withZone(ZoneId zone) { this }
            Instant instant() { current.get() }
        }
        expect:
        TimeProvider.now() == Instant.EPOCH
        when:
        current.set(Instant.EPOCH.plusSeconds(61))
        then:
        TimeProvider.now() == current.get()
        TimeProvider.millis() == 61000
    }

    def "分钟点按延迟回退并向下对齐分钟，跨小时边界正确"() {
        given:
        TimeProvider.clock = Clock.fixed(Instant.parse('2026-10-03T02:00:30.500Z'), ZoneOffset.UTC)
        expect:
        TimeProvider.delayedMinuteStart(5) == Instant.parse('2026-10-03T02:00:00Z')
        TimeProvider.delayedMinuteStart(90) == Instant.parse('2026-10-03T01:59:00Z')
        TimeProvider.delayedMinuteStart(0) == Instant.parse('2026-10-03T02:00:00Z')
    }
}
