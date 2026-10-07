package com.neocat.alert.infra.jdbc

import com.neocat.alert.domain.engine.*
import com.neocat.alert.domain.recipient.*
import com.neocat.alert.domain.rule.*
import com.neocat.organization.api.internal.OrgResourceIndex
import com.neocat.query.domain.stat.Stat
import spock.lang.Specification

class AlertRuleProjectionSpec extends Specification {
    def 'saving an organization rule synchronously projects its allocated ID'() {
        given:
        def mapper = Mock(AlertMapper)
        def index = Mock(OrgResourceIndex)
        def repository = new AlertRuleRepositoryAdapter(mapper, index)
        def rule = AlertRule.draft(AlertScope.ORGANIZATION, 7L, '告警', '',
                AlertTarget.rawMetric('order', 'TRANSACTION', 'URL', '/a'),
                Combinator.AND, 1, [new Condition(Stat.HITS, Comparator.GT, 1d)],
                [1L], [AlertChannel.EMAIL])

        when:
        def saved = repository.save(rule)

        then:
        1 * mapper.insertRule({ row -> row.orgId == 7L }) >> { args -> args[0].id = 42L; 1 }
        1 * mapper.insertCondition(42L, 'HITS', 'GT', 1d)
        1 * mapper.insertRecipient(42L, 1L, 'EMAIL')
        1 * index.alertRule(7L, 42L)
        saved.getId() == 42L
    }
}
