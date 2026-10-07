package com.neocat.alert.domain.rule

import com.neocat.alert.domain.engine.AlertWindowState

import com.neocat.query.domain.stat.Stat
import spock.lang.Specification

class RuleMutationSpec extends Specification {
    static final long T = 1_790_000_000_000L

    AlertRuleRepository repository = Mock()
    AlertLifecycleService service = new AlertLifecycleService(repository)

    static AlertRule rule(long id = 1L, boolean enabled = false) {
        new AlertRule(id, AlertScope.SERVICE, null, '订单失败率', '',
                AlertTarget.rawMetric('order', 'TRANSACTION', 'URL', '/a'),
                Combinator.AND, 3, [new Condition(Stat.HITS, Comparator.GT, 1d)],
                [1L], [AlertChannel.EMAIL], enabled, false, enabled ? T : null)
    }

    def 'new rules are disabled and have no activation baseline'() {
        expect:
        !rule(0L).isEnabled()
        rule(0L).getStateSince() == null
    }

    def 'enabling an existing rule clears prior points and starts a fresh window'() {
        given:
        def existing = rule()

        when:
        def updated = service.enable(1L, T)

        then:
        1 * repository.findById(1L) >> existing
        1 * repository.clearWindowState(1L)
        1 * repository.saveWindowState(AlertWindowState.empty(1L, T))
        1 * repository.save({ it.isEnabled() && it.getStateSince() == T }) >> { args -> args[0] }
        updated.isEnabled()
        updated.getStateSince() == T
    }

    def 're-enabling moves the baseline forward instead of reusing previous points'() {
        given:
        def existing = rule(1L, true)

        when:
        def updated = service.enable(1L, T + 60_000L)

        then:
        1 * repository.findById(1L) >> existing
        1 * repository.clearWindowState(1L)
        1 * repository.saveWindowState(AlertWindowState.empty(1L, T + 60_000L))
        1 * repository.save({ it.getStateSince() == T + 60_000L }) >> { args -> args[0] }
        updated.isEnabled()
    }

    def 'disabling clears the window and activation baseline'() {
        when:
        def updated = service.disable(1L)

        then:
        1 * repository.findById(1L) >> rule(1L, true)
        1 * repository.clearWindowState(1L)
        1 * repository.save({ !it.isEnabled() && it.getStateSince() == null }) >> { args -> args[0] }
        !updated.isEnabled()
    }

    def 'editing even an enabled rule preserves ID but requires manual re-enabling'() {
        given:
        def edited = rule(0L).withId(0L)

        when:
        def result = service.edit(1L, new AlertRule(0L, edited.getScope(), edited.getOrgId(),
                '新名字', edited.getDescription(), edited.getTarget(), edited.getCombinator(), 5,
                edited.getConditions(), edited.getRecipients(), edited.getChannels(), true, false, T))

        then:
        1 * repository.findById(1L) >> rule(1L, true)
        1 * repository.clearWindowState(1L)
        1 * repository.save({ it.getId() == 1L && it.getName() == '新名字' && it.getWindowPoints() == 5 &&
                !it.isEnabled() && it.getStateSince() == null }) >> { args -> args[0] }
        result.getId() == 1L
        !result.isEnabled()
    }

    def 'an unknown rule is rejected before changing its window or contents'() {
        when:
        service.edit(9999L, rule(0L))

        then:
        1 * repository.findById(9999L) >> null
        0 * repository.clearWindowState(_)
        0 * repository.save(_)
        thrown(NoSuchElementException)
    }

    def 'deletion removes both the rule and its window'() {
        when:
        service.delete(1L)

        then:
        1 * repository.findById(1L) >> rule()
        1 * repository.delete(1L)
        1 * repository.clearWindowState(1L)
    }

    def 'no persisted trigger history or acknowledgement state is modeled'() {
        expect:
        AlertRuleRepository.declaredMethods*.name.every {
            !it.toLowerCase().contains('history') && !it.toLowerCase().contains('trigger')
        }
        !AlertRule.declaredFields*.name.any { it.toLowerCase() in ['acked', 'silenced', 'recovered'] }
    }
}
