package com.neocat.alert.application.handler

import com.neocat.alert.domain.entity.AlertRule
import com.neocat.alert.domain.entity.vo.rule.AlertChannel
import com.neocat.alert.domain.entity.vo.rule.AlertScope
import com.neocat.alert.domain.entity.vo.rule.AlertTarget
import com.neocat.alert.domain.entity.vo.rule.Combinator
import com.neocat.alert.domain.entity.vo.rule.Comparator
import com.neocat.alert.domain.entity.vo.rule.Condition
import com.neocat.alert.domain.repository.AlertRuleRepository
import com.neocat.identity.api.internal.AccountStatusChanged
import com.neocat.organization.api.internal.EffectiveMembershipChanged
import com.neocat.query.domain.stat.Stat
import spock.lang.Specification

class RecipientEventHandlerSpec extends Specification {
    AlertRuleRepository rules = Mock()
    RecipientEventHandler handler = new RecipientEventHandler(rules)

    static AlertRule rule(long id, AlertScope scope, Long orgId, List<String> recipients) {
        new AlertRule(id, scope, orgId, '订单失败率', '',
                AlertTarget.rawMetric('order', 'TRANSACTION', 'URL', '/a'),
                Combinator.AND, 3, [new Condition(Stat.HITS, Comparator.GT, 1d)],
                recipients, [AlertChannel.EMAIL], true, false)
    }

    def 'disabling an account removes it from all rules without switching the rules off'() {
        given:
        def matching = rule(1L, AlertScope.SERVICE, null, ['keith.ji', 'alice'])
        def unrelated = rule(2L, AlertScope.ORGANIZATION, 7L, ['bob'])

        when:
        handler.on(new AccountStatusChanged('keith.ji', false))

        then:
        1 * rules.findAll() >> [matching, unrelated]
        1 * rules.save({ it.getId() == 1L && it.getRecipients() == ['alice'] && it.isEnabled() }) >> { args -> args[0] }
    }

    def 're-enabling an account never restores the removed recipient relation'() {
        when:
        handler.on(new AccountStatusChanged('keith.ji', true))

        then:
        0 * rules._
    }

    def 'losing a leaf membership only removes recipients of that leaf'() {
        given:
        def matching = rule(1L, AlertScope.ORGANIZATION, 7L, ['keith.ji', 'alice'])

        when:
        handler.on(new EffectiveMembershipChanged('keith.ji', 7L, false))

        then:
        1 * rules.byOrg(7L) >> [matching]
        1 * rules.save({ it.getOrgId() == 7L && it.getRecipients() == ['alice'] }) >> { args -> args[0] }
        0 * rules.findAll()
    }

    def 'gaining membership does not opt an account into notifications'() {
        when:
        handler.on(new EffectiveMembershipChanged('keith.ji', 7L, true))

        then:
        0 * rules._
    }

    def 'account removal keeps the window baseline and enabled flag of untouched rules'() {
        given:
        def untouched = rule(3L, AlertScope.SERVICE, null, ['alice'])

        when:
        handler.on(new AccountStatusChanged('keith.ji', false))

        then:
        1 * rules.findAll() >> [untouched]
        0 * rules.save(_)
        0 * rules.clearAlertEvalCursor(_)
    }
}
