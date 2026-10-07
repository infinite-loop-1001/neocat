package com.neocat.analysis.infra.job

import com.neocat.analysis.domain.schedule.ReportScheduler
import com.neocat.analysis.domain.bucket.ReportBucketSinkPort
import com.neocat.analysis.infra.store.MinuteBucketReader
import com.neocat.common.config.ReportConfig
import com.neocat.common.config.IngestConfig
import spock.lang.Specification
import java.time.*

class DynamicReportScheduleSpec extends Specification {
    def "分钟调度读取新延迟，去重已刷分钟，延迟加大不倒退"() {
        given:
        def scheduler = Mock(ReportScheduler)
        def clock = Mock(Clock)
        def job = new ReportSchedulerJob(scheduler, Mock(ReportBucketSinkPort), Mock(MinuteBucketReader), clock)
        clock.instant() >> Instant.parse('2026-10-03T12:02:30Z')

        when:
        job.flushMinute()
        job.flushMinute()

        then:
        1 * scheduler.flushCompletedMinute(Instant.parse('2026-10-03T12:02:25Z')) >> 1
        1 * scheduler.refreshLateHours(_, 2)

        when:
        ReportConfig.MINUTE_FLUSH_DELAY_SECONDS = 90
        job.flushMinute()

        then:
        0 * scheduler._

        when:
        clock.instant() >> Instant.parse('2026-10-03T12:05:30Z')
        IngestConfig.ACCEPT_LATE_HOURS = 3
        job.flushMinute()

        then:
        1 * scheduler.flushCompletedMinute(Instant.parse('2026-10-03T12:04:00Z')) >> 1
        1 * scheduler.refreshLateHours(_, 3)
    }

    def "失败分钟允许重试，不缓存配置值"() {
        given:
        def scheduler = Mock(ReportScheduler)
        def job = new ReportSchedulerJob(scheduler, Mock(ReportBucketSinkPort), Mock(MinuteBucketReader),
                Clock.fixed(Instant.parse('2026-10-03T12:02:30Z'), ZoneOffset.UTC))

        when:
        job.flushMinute()
        job.flushMinute()

        then:
        2 * scheduler.flushCompletedMinute(_) >> { throw new IllegalStateException('write failed') } >> 1
        1 * scheduler.refreshLateHours(_, 2)
    }
}
