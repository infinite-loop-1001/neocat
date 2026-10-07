package com.neocat.organization.infra.projection

import com.neocat.organization.domain.lifecycle.OrgDeletionRequested
import org.springframework.context.ApplicationEventPublisher
import org.springframework.jdbc.core.JdbcTemplate
import spock.lang.Specification

class OrgResourceProjectionSpec extends Specification {
    JdbcTemplate jdbc = Mock()
    ApplicationEventPublisher events = Mock()
    OrgResourceProjection projection = new OrgResourceProjection(jdbc, events)

    def 'a missing resource projection never returns an invented resource count'() {
        when:
        projection.hasDashboards(7L)

        then:
        1 * jdbc.queryForObject(_ as String, Long, 7L, 'DASHBOARD') >> {
            throw new IllegalStateException('MySQL unavailable')
        }
        thrown(IllegalStateException)
    }

    def 'deletion publishes a synchronous request then refuses to proceed if anything remains'() {
        when:
        projection.deleteAllOf(7L)

        then:
        1 * jdbc.queryForObject(_ as String, Long, 7L, 'DASHBOARD') >> 1L
        1 * jdbc.queryForObject('SELECT COUNT(*) FROM nc_dashboard WHERE org_id = ?', Long, 7L) >> 1L
        1 * jdbc.queryForObject({ it.contains('LEFT JOIN nc_org_resource') }, Long, 7L) >> 0L
        1 * jdbc.queryForObject(_ as String, Long, 7L, 'ALERT_RULE') >> 0L
        1 * jdbc.queryForObject('SELECT COUNT(*) FROM nc_alert_rule WHERE org_id = ?', Long, 7L) >> 0L
        1 * jdbc.queryForObject({ it.contains('nc_alert_rule a LEFT JOIN') }, Long, 7L) >> 0L
        1 * events.publishEvent(new OrgDeletionRequested(7L))
        1 * jdbc.queryForObject(_ as String, Long, 7L, 'DASHBOARD') >> 1L
        1 * jdbc.queryForObject('SELECT COUNT(*) FROM nc_dashboard WHERE org_id = ?', Long, 7L) >> 1L
        1 * jdbc.queryForObject({ it.contains('LEFT JOIN nc_org_resource') }, Long, 7L) >> 0L
        thrown(IllegalStateException)
    }

    def 'card count requires an existing dashboard projection'() {
        when:
        projection.cardCount(7L, 12L, 3L)

        then:
        1 * jdbc.update({ it.contains('UPDATE nc_org_resource') }, 3L, 7L, 12L) >> 0
        1 * jdbc.queryForObject({ it.contains("resource_id = ?") }, Long, 7L, 12L) >> 0L
        thrown(IllegalStateException)
    }

    def 'writing an unchanged card count does not imply a missing projection row'() {
        when:
        projection.cardCount(7L, 12L, 3L)

        then:
        1 * jdbc.update({ it.contains('UPDATE nc_org_resource') }, 3L, 7L, 12L) >> 0
        1 * jdbc.queryForObject({ it.contains("resource_id = ?") }, Long, 7L, 12L) >> 1L
        noExceptionThrown()
    }

    def 'equal counts with different resource IDs cannot pass as consistent'() {
        when:
        projection.hasAlertRules(7L)

        then:
        1 * jdbc.queryForObject(_ as String, Long, 7L, 'ALERT_RULE') >> 1L
        1 * jdbc.queryForObject('SELECT COUNT(*) FROM nc_alert_rule WHERE org_id = ?', Long, 7L) >> 1L
        1 * jdbc.queryForObject({ it.contains('LEFT JOIN nc_org_resource') }, Long, 7L) >> 1L
        thrown(IllegalStateException)
    }
}
