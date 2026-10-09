package com.neocat.analysis.domain.schedule

import com.neocat.analysis.domain.bucket.AggregatedRow
import com.neocat.analysis.domain.bucket.AggregationLevel
import com.neocat.analysis.domain.bucket.MinuteBucketSource
import com.neocat.analysis.domain.bucket.ReportBucketSinkPort
import com.neocat.analysis.domain.bucket.SeriesKey
import com.neocat.analysis.domain.bucket.SeriesKind

import spock.lang.Specification

import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime

class ReportSchedulerSpec extends Specification {
    static final ZoneId SH = ZoneId.of('Asia/Shanghai')

    MinuteBucketSource reader = Mock()
    ReportBucketSinkPort sink = Mock()
    ReportScheduler scheduler = new ReportScheduler(reader, sink, { SH })

    static Instant sh(int y, int mo, int d, int h, int mi) {
        ZonedDateTime.of(y, mo, d, h, mi, 0, 0, SH).toInstant()
    }

    def key = SeriesKey.of('order', SeriesKind.TRANSACTION, 'URL', '/a')

    AggregatedRow row(Instant start, long count, long durationSum) {
        new AggregatedRow(key, start, AggregationLevel.MINUTE, 60).tap {
            it.addCount(count, 0L, durationSum, 10L, 100L)
        }
    }

    def 'only the previously completed minute is read and persisted'() {
        given:
        def now = sh(2026, 9, 24, 12, 23).plusSeconds(41)
        def finished = row(sh(2026, 9, 24, 12, 22), 100, 1000)

        when:
        def count = scheduler.flushCompletedMinute(now)

        then:
        1 * reader.readMinute(sh(2026, 9, 24, 12, 22)) >> [finished]
        1 * sink.writeMinuteBuckets([finished])
        0 * reader.readMinute(sh(2026, 9, 24, 12, 23))
        count == 1
        scheduler.currentLag(sh(2026, 9, 24, 12, 23).plusSeconds(5)).seconds == 65
    }

    def 'no minute row is written when the source is empty'() {
        when:
        def count = scheduler.flushCompletedMinute(sh(2026, 9, 24, 12, 23))

        then:
        1 * reader.readMinute(sh(2026, 9, 24, 12, 22)) >> []
        0 * sink.writeMinuteBuckets(_)
        count == 0
    }

    def 'a complete hour is aggregated by the reader then cleared after a successful write'() {
        given:
        def hour = sh(2026, 9, 24, 12, 0)
        def minutes = [row(hour, 100, 1000), row(hour.plusSeconds(60), 200, 4000)]
        def aggregated = row(hour, 300, 5000)

        when:
        def count = scheduler.rollupCompletedHour(sh(2026, 9, 24, 13, 0).plusSeconds(2))

        then:
        1 * reader.readHour(hour) >> minutes
        1 * reader.aggregate(minutes, AggregationLevel.HOUR) >> [aggregated]
        1 * sink.writeHourBuckets([aggregated])
        1 * sink.writeMinuteBuckets(minutes)
        1 * reader.clearHour(hour)
        count == 1
    }

    def 'late window retains accepted hours and refreshes minute snapshots before finalizing'() {
        given:
        def previous = sh(2026, 10, 2, 11, 0)
        def expired = previous.minusSeconds(3600)
        def minutes = [row(previous, 2, 20)]
        def hourly = row(previous, 2, 20)
        when:
        scheduler.refreshLateHours(sh(2026, 10, 2, 12, 30), 2)
        then:
        1 * reader.readHour(previous) >> minutes
        1 * sink.writeMinuteBuckets(minutes)
        1 * reader.aggregate(minutes, AggregationLevel.HOUR) >> [hourly]
        1 * sink.writeHourBuckets([hourly])
        1 * reader.readHour(expired) >> []
        1 * reader.clearHour(expired)
        0 * reader.clearHour(previous)
    }

    def 'failed late refresh keeps the buckets for retry rather than releasing observations'() {
        given:
        def hour = sh(2026, 10, 2, 11, 0)
        def minutes = [row(hour, 2, 20)]
        when:
        scheduler.refreshLateHours(sh(2026, 10, 2, 12, 30), 1)
        then:
        1 * reader.readHour(hour) >> minutes
        1 * sink.writeMinuteBuckets(minutes) >> { throw new IllegalStateException('database down') }
        0 * reader.clearHour(_)
        thrown(IllegalStateException)
    }

    def 'no data still releases the completed hour, never the current hour'() {
        given:
        def previous = sh(2026, 9, 24, 11, 0)

        when:
        def count = scheduler.rollupCompletedHour(sh(2026, 9, 24, 12, 30))

        then:
        1 * reader.readHour(previous) >> []
        1 * reader.clearHour(previous)
        0 * sink.writeHourBuckets(_)
        count == 0
    }

    def 'daily rollup reads only the previous natural day in the platform zone'() {
        given:
        def from = sh(2026, 9, 24, 0, 0)
        def to = sh(2026, 9, 25, 0, 0)
        def hourly = [row(sh(2026, 9, 24, 10, 0), 100, 1000)]
        def daily = [row(from, 100, 1000)]

        when:
        def count = scheduler.rollupCompletedDay(sh(2026, 9, 25, 0, 5))

        then:
        1 * sink.readBuckets(AggregationLevel.HOUR, from, to) >> hourly
        1 * reader.aggregate(hourly, AggregationLevel.DAY) >> daily
        1 * sink.writeDayBuckets(daily)
        count == 1
    }

    def 'weekly and monthly windows align to local Monday and month start'() {
        when:
        scheduler.rollupCompletedWeek(sh(2026, 9, 28, 0, 10))
        scheduler.rollupCompletedMonth(sh(2026, 10, 1, 0, 10))

        then:
        1 * sink.readBuckets(AggregationLevel.HOUR, sh(2026, 9, 21, 0, 0),
                sh(2026, 9, 28, 0, 0)) >> []
        1 * sink.readBuckets(AggregationLevel.HOUR, sh(2026, 9, 1, 0, 0),
                sh(2026, 10, 1, 0, 0)) >> []
        0 * reader.aggregate(_, _)
        0 * sink.writeWeekBuckets(_)
        0 * sink.writeMonthBuckets(_)
    }

    def 'retention thresholds use configured days and months in platform time'() {
        when:
        def result = scheduler.evictExpired(sh(2026, 9, 24, 2, 0), 30, 30, 13)

        then:
        1 * sink.evictMinuteBucketsBefore(sh(2026, 8, 25, 2, 0)) >> 3L
        1 * sink.evictHourBucketsBefore(sh(2026, 8, 25, 2, 0)) >> 2L
        1 * sink.evictLongTermBucketsBefore(sh(2025, 8, 24, 2, 0)) >> 1L
        result == new EvictionResult(3L, 2L, 1L)
    }

    def 'retention policy can be changed without rebuilding scheduler'() {
        when:
        scheduler.evictExpired(sh(2026, 9, 24, 2, 0), 7, 90, 24)

        then:
        1 * sink.evictMinuteBucketsBefore(sh(2026, 9, 17, 2, 0)) >> 0L
        1 * sink.evictHourBucketsBefore(sh(2026, 6, 26, 2, 0)) >> 0L
        1 * sink.evictLongTermBucketsBefore(sh(2024, 9, 24, 2, 0)) >> 0L
    }
}
