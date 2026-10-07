package com.neocat.alert.infra.listener

import com.neocat.alert.domain.engine.*
import com.neocat.alert.domain.recipient.*
import com.neocat.alert.domain.rule.*
import com.neocat.dashboard.api.internal.CardChange
import com.neocat.dashboard.api.internal.CardReferences
import com.neocat.query.domain.stat.Stat
import spock.lang.Specification

class CardChangeListenerSpec extends Specification {
    AlertRuleRepository rules = Mock()
    CardReferences references = Mock()
    CardChangeListener listener = new CardChangeListener(rules, references)

    static AlertRule rule(AlertTarget target, boolean enabled = true) {
        new AlertRule(4L, AlertScope.ORGANIZATION, 7L, '告警', '', target,
                Combinator.AND, 1, [new Condition(Stat.HITS, Comparator.GT, 0d)],
                [1L], [AlertChannel.EMAIL], enabled, false, enabled ? 100L : null)
    }

    def 'card formula change closes only that card rule, replacing its formula inputs'() {
        given:
        def current = rule(AlertTarget.cardResult(12L, 'order', 'TRANSACTION', 'URL', '/a', [Stat.HITS]))
        def event = new CardChange(12L, 7L, 'order', 'TRANSACTION', 'URL', '/a', null,
                'failures / hits', ['FAILURES', 'HITS'], false)

        when:
        listener.on(event)

        then:
        1 * rules.byOrg(7L) >> [current]
        1 * rules.clearWindowState(4L)
        1 * rules.save({ it.getTarget().getFormulaStats() == [Stat.FAILURES, Stat.HITS]
                && !it.isEnabled() && it.getStateSince() == null && !it.isInvalid() }) >> { args -> args[0] }
        0 * references._
    }

    def 'deleting a card invalidates its result rule but preserves its configuration'() {
        given:
        def current = rule(AlertTarget.cardResult(12L, 'order', 'TRANSACTION', 'URL', '/a', [Stat.HITS]))
        def event = new CardChange(12L, 7L, 'order', 'TRANSACTION', 'URL', '/a', null,
                null, ['HITS'], true)

        when:
        listener.on(event)

        then:
        1 * rules.byOrg(7L) >> [current]
        1 * rules.clearWindowState(4L)
        1 * rules.save({ it.isInvalid() && !it.isEnabled() && it.getTarget() == current.getTarget() }) >>
                { args -> args[0] }
    }

    def 'raw stat rule remains enabled if another card in the same leaf still references it'() {
        given:
        def current = rule(AlertTarget.rawMetric('order', 'TRANSACTION', 'URL', '/a'))
        def event = new CardChange(12L, 7L, 'order', 'TRANSACTION', 'URL', '/a', null,
                null, ['HITS'], true)

        when:
        listener.on(event)

        then:
        1 * rules.byOrg(7L) >> [current]
        1 * references.referenced(7L, 'order', 'TRANSACTION', 'URL', '/a', null, 'HITS') >> true
        0 * rules.save(_)
        0 * rules.clearWindowState(_)
    }

    def 'raw stat rule is invalidated once its last card reference disappears'() {
        given:
        def current = rule(AlertTarget.rawMetric('order', 'TRANSACTION', 'URL', '/a'))
        def event = new CardChange(12L, 7L, 'order', 'TRANSACTION', 'URL', '/a', null,
                null, ['HITS'], true)

        when:
        listener.on(event)

        then:
        1 * rules.byOrg(7L) >> [current]
        1 * references.referenced(7L, 'order', 'TRANSACTION', 'URL', '/a', null, 'HITS') >> false
        1 * rules.clearWindowState(4L)
        1 * rules.save({ it.isInvalid() && !it.isEnabled() }) >> { args -> args[0] }
    }
}
