package com.neocat.alert.domain.recipient

import com.neocat.alert.domain.engine.AlertWindowState
import com.neocat.alert.domain.rule.AlertChannel
import com.neocat.alert.domain.rule.AlertRule
import com.neocat.alert.domain.rule.AlertRuleRepository
import com.neocat.alert.domain.rule.AlertScope
import com.neocat.alert.domain.rule.AlertTarget
import com.neocat.alert.domain.rule.Combinator
import com.neocat.alert.domain.rule.Comparator
import com.neocat.alert.domain.rule.Condition

import com.neocat.query.domain.stat.Stat
import spock.lang.Specification

class RecipientSpec extends Specification {
    static final long T = 1_790_000_000_000L

    AlertRuleRepository repository = Mock()
    RecipientGateway gateway = Mock()
    RecipientService service = new RecipientService(repository, gateway)

    static AlertRule rule(long id, AlertScope scope, Long orgId, List<Long> recipients,
                          boolean enabled = true) {
        new AlertRule(id, scope, orgId, '订单失败率', '',
                AlertTarget.rawMetric('order', 'TRANSACTION', 'URL', '/a'),
                Combinator.AND, 3, [new Condition(Stat.HITS, Comparator.GT, 1d)],
                recipients, [AlertChannel.EMAIL], enabled, false, enabled ? T : null)
    }

    def 'service alert accepts enabled accounts without organization membership'() {
        when:
        service.validateSelection(rule(1L, AlertScope.SERVICE, null, [1L, 2L]), [1L, 2L])

        then:
        1 * gateway.isEnabled(1L) >> true
        1 * gateway.isEnabled(2L) >> true
        0 * gateway.isEffectiveMember(_, _)
        noExceptionThrown()
    }

    def 'organization alert accepts only enabled effective leaf members'() {
        when:
        service.validateSelection(rule(1L, AlertScope.ORGANIZATION, 7L, []), [2L])

        then:
        1 * gateway.isEnabled(2L) >> true
        1 * gateway.isEffectiveMember(2L, 7L) >> false
        thrown(IllegalArgumentException)
    }

    def 'disabled accounts cannot be selected even when they are members'() {
        when:
        service.validateSelection(rule(1L, AlertScope.ORGANIZATION, 7L, []), [2L])

        then:
        1 * gateway.isEnabled(2L) >> false
        0 * gateway.isEffectiveMember(_, _)
        thrown(IllegalArgumentException)
    }

    def 'disabling an account removes it from all rules without switching the rules off'() {
        given:
        def matching = rule(1L, AlertScope.SERVICE, null, [1L, 2L])
        def unrelated = rule(2L, AlertScope.ORGANIZATION, 7L, [3L])

        when:
        def changed = service.onEvent(new RecipientEvent.UserDisabled(1L))

        then:
        1 * repository.findAll() >> [matching, unrelated]
        1 * repository.save({ it.getId() == 1L && it.getRecipients() == [2L]
                && it.isEnabled() && it.getStateSince() == T }) >> { args -> args[0] }
        changed*.getId() == [1L]
    }

    def 'enabling a user does not silently restore any recipient relation'() {
        when:
        def changed = service.onEvent(new RecipientEvent.UserEnabled(1L))

        then:
        changed.isEmpty()
        0 * repository._
    }

    def 'losing a leaf membership only removes recipients of that leaf'() {
        given:
        def matching = rule(1L, AlertScope.ORGANIZATION, 7L, [1L, 2L])

        when:
        def changed = service.onEvent(new RecipientEvent.OrgMembershipChanged(1L, 7L, false))

        then:
        1 * repository.byOrg(7L) >> [matching]
        1 * repository.save({ it.getOrgId() == 7L && it.getRecipients() == [2L] }) >> { args -> args[0] }
        0 * repository.findAll()
        changed.size() == 1
    }

    def 'gaining membership does not opt an account into notifications'() {
        when:
        def changed = service.onEvent(new RecipientEvent.OrgMembershipChanged(1L, 7L, true))

        then:
        changed.isEmpty()
        0 * repository._
    }

    def 'adding recipients deduplicates them and resets the window baseline'() {
        given:
        def original = rule(4L, AlertScope.SERVICE, null, [])

        when:
        def updated = service.updateRecipients(4L, [3L, 3L], T + 60_000L)

        then:
        1 * repository.findById(4L) >> original
        1 * repository.save({ it.getRecipients() == [3L] && it.isEnabled() &&
                it.getConditions() == original.getConditions() }) >> { args -> args[0] }
        1 * repository.clearWindowState(4L)
        1 * repository.saveWindowState(AlertWindowState.empty(4L, T + 60_000L))
        updated.getRecipients() == [3L]
    }

    def 'effective recipients exclude disabled users and former organization members'() {
        given:
        def original = rule(4L, AlertScope.ORGANIZATION, 7L, [1L, 2L, 3L])

        when:
        def effective = service.effectiveRecipients(original)

        then:
        1 * gateway.isEnabled(1L) >> true
        1 * gateway.isEffectiveMember(1L, 7L) >> true
        1 * gateway.isEnabled(2L) >> true
        1 * gateway.isEffectiveMember(2L, 7L) >> false
        1 * gateway.isEnabled(3L) >> false
        effective == [1L]
    }

    def 'org deleted event disables its rules but retains their configuration'() {
        given:
        def original = rule(4L, AlertScope.ORGANIZATION, 7L, [1L])

        when:
        def changed = service.onEvent(new RecipientEvent.OrgDeleted(7L))

        then:
        1 * repository.byOrg(7L) >> [original]
        1 * repository.save({ it.isInvalid() && !it.isEnabled() && it.getName() == original.getName() }) >>
                { args -> args[0] }
        changed.size() == 1
    }

    def 'all recipient event types remain representable'() {
        expect:
        RecipientEvent.declaredClasses*.simpleName as Set ==
                ['UserDisabled', 'UserEnabled', 'OrgMembershipChanged', 'OrgDeleted'] as Set
    }
}
