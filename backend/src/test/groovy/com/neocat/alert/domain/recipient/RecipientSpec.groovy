package com.neocat.alert.domain.recipient

import com.neocat.alert.domain.entity.vo.cursor.AlertEvalCursor
import com.neocat.alert.domain.entity.vo.rule.AlertChannel
import com.neocat.alert.domain.entity.AlertRule
import com.neocat.alert.domain.gateway.RecipientGateway
import com.neocat.alert.domain.repository.AlertRuleRepository
import com.neocat.alert.domain.entity.vo.rule.AlertScope
import com.neocat.alert.domain.entity.vo.rule.AlertTarget
import com.neocat.alert.domain.entity.vo.rule.Combinator
import com.neocat.alert.domain.entity.vo.rule.Comparator
import com.neocat.alert.domain.entity.vo.rule.Condition
import com.neocat.query.domain.stat.Stat
import spock.lang.Specification

class RecipientSpec extends Specification {
    static final long T = 1_790_000_000_000L

    AlertRuleRepository repository = Mock()
    RecipientGateway gateway = Mock()
    RecipientService service = new RecipientService(repository, gateway)

    static AlertRule rule(long id, AlertScope scope, Long orgId, List<String> userAccounts) {
        new AlertRule(id, scope, orgId, '订单失败率', '',
                AlertTarget.rawMetric('order', 'TRANSACTION', 'URL', '/a'),
                Combinator.AND, 3, [new Condition(Stat.HITS, Comparator.GT, 1d)],
                userAccounts, [AlertChannel.EMAIL], true, false)
    }

    def 'service alert accepts enabled accounts without organization membership'() {
        when:
        service.validateSelection(rule(1L, AlertScope.SERVICE, null, ['keith.ji', 'alice']), ['keith.ji', 'alice'])

        then:
        1 * gateway.isEnabled('keith.ji') >> true
        1 * gateway.isEnabled('alice') >> true
        0 * gateway.isEffectiveMember(_, _)
        noExceptionThrown()
    }

    def 'organization alert accepts only enabled effective leaf members'() {
        when:
        service.validateSelection(rule(1L, AlertScope.ORGANIZATION, 7L, []), ['alice'])

        then:
        1 * gateway.isEnabled('alice') >> true
        1 * gateway.isEffectiveMember('alice', 7L) >> false
        thrown(IllegalArgumentException)
    }

    def 'disabled accounts cannot be selected even when they are members'() {
        when:
        service.validateSelection(rule(1L, AlertScope.ORGANIZATION, 7L, []), ['alice'])

        then:
        1 * gateway.isEnabled('alice') >> false
        0 * gateway.isEffectiveMember(_, _)
        thrown(IllegalArgumentException)
    }

    def 'adding recipients deduplicates them and resets the window baseline'() {
        given:
        def original = rule(4L, AlertScope.SERVICE, null, [])

        when:
        def updated = service.updateRecipients(4L, ['keith.ji', 'keith.ji'], T + 60_000L)

        then:
        1 * repository.findById(4L) >> original
        1 * repository.save({ it.getRecipients() == ['keith.ji'] && it.isEnabled() &&
                it.getConditions() == original.getConditions() }) >> { args -> args[0] }
        1 * repository.clearAlertEvalCursor(4L)
        1 * repository.saveAlertEvalCursor(AlertEvalCursor.empty(4L, T + 60_000L))
        updated.getRecipients() == ['keith.ji']
    }

    def 'effective recipients exclude members who lost the leaf, keeping enabled ones'() {
        given:
        def original = rule(4L, AlertScope.ORGANIZATION, 7L, ['keith.ji', 'alice'])

        when:
        def effective = service.effectiveRecipients(original)

        then:
        1 * gateway.isEffectiveMember('keith.ji', 7L) >> true
        1 * gateway.isEffectiveMember('alice', 7L) >> false
        effective == ['keith.ji']
    }

    def 'service alert effective recipients keep every listed account'() {
        given:
        def original = rule(5L, AlertScope.SERVICE, null, ['keith.ji', 'bob'])

        when:
        def effective = service.effectiveRecipients(original)

        then:
        0 * gateway._
        effective == ['keith.ji', 'bob']
    }
}
