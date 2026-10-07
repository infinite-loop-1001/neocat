package com.neocat.query.infra.datasource

import com.neocat.query.infra.port.ReportDataPort

import com.neocat.common.time.bucket.Granularity
import spock.lang.Specification

import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

class ReportDataPortRouterSpec extends Specification {
    def "跨当前小时边界时分别读取历史和内存并合并目录"() {
        given:
        def history = Mock(ReportDataPort)
        def current = Mock(ReportDataPort)
        def now = Instant.parse('2026-10-01T10:35:00Z')
        def from = Instant.parse('2026-10-01T09:30:00Z')
        def to = Instant.parse('2026-10-01T10:35:00Z')
        def boundary = Instant.parse('2026-10-01T10:00:00Z')
        def router = new ReportDataPortRouter(history, current, Clock.fixed(now, ZoneOffset.UTC), { ZoneOffset.UTC })

        when:
        def types = router.typesOf('TRANSACTION', 'app', from, to)

        then:
        1 * history.typesOf('TRANSACTION', 'app', from, boundary) >> ['URL', 'SQL']
        1 * current.typesOf('TRANSACTION', 'app', boundary, to) >> ['URL', 'CALL']
        types == ['URL', 'SQL', 'CALL']
        0 * _
    }

    def "完全落在历史时不读内存，当前小时的丢弃质量不伪装成历史"() {
        given:
        def history = Mock(ReportDataPort)
        def current = Mock(ReportDataPort)
        def router = new ReportDataPortRouter(history, current,
                Clock.fixed(Instant.parse('2026-10-01T10:35:00Z'), ZoneOffset.UTC), { ZoneOffset.UTC })
        def from = Instant.parse('2026-10-01T08:00:00Z')
        def to = Instant.parse('2026-10-01T09:00:00Z')

        when:
        def rows = router.rows('TRANSACTION', 'app', 'URL', '/a', from, to, Granularity.MINUTE_1, [])
        def drop = router.droppedAt('TRANSACTION', 'app', 'URL', '/a', Instant.parse('2026-10-01T10:00:00Z'))

        then:
        1 * history.rows('TRANSACTION', 'app', 'URL', '/a', from, to, Granularity.MINUTE_1, []) >> []
        rows == []
        !drop
        0 * current._
    }

    def "边界上的范围只读内存，已结束的小时只读历史"() {
        given:
        def history = Mock(ReportDataPort)
        def current = Mock(ReportDataPort)
        def boundary = Instant.parse('2026-10-01T10:00:00Z')
        def now = Instant.parse('2026-10-01T10:35:00Z')
        def router = new ReportDataPortRouter(history, current, Clock.fixed(now, ZoneOffset.UTC), { ZoneOffset.UTC })

        when:
        def live = router.typesOf('TRANSACTION', 'app', boundary, now)
        def past = router.typesOf('TRANSACTION', 'app', boundary.minusSeconds(3600), boundary)

        then:
        1 * current.typesOf('TRANSACTION', 'app', boundary, now) >> ['URL']
        1 * history.typesOf('TRANSACTION', 'app', boundary.minusSeconds(3600), boundary) >> ['SQL']
        live == ['URL']
        past == ['SQL']
        0 * _
    }

    def "小时边界按平台时区而非固定 UTC 整点划分"() {
        given:
        def history = Mock(ReportDataPort)
        def current = Mock(ReportDataPort)
        def now = Instant.parse('2026-10-01T10:35:00Z')
        def boundary = Instant.parse('2026-10-01T10:30:00Z')
        def from = Instant.parse('2026-10-01T10:20:00Z')
        def router = new ReportDataPortRouter(history, current, Clock.fixed(now, ZoneOffset.UTC),
                { ZoneOffset.ofHoursMinutes(5, 30) })

        when:
        router.typesOf('TRANSACTION', 'app', from, now)

        then:
        1 * history.typesOf('TRANSACTION', 'app', from, boundary) >> []
        1 * current.typesOf('TRANSACTION', 'app', boundary, now) >> []
        0 * _
    }
}
