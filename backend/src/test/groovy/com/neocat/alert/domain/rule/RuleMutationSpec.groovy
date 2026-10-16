package com.neocat.alert.domain.rule

import com.neocat.alert.domain.AlertLifecycleService
import com.neocat.alert.domain.entity.AlertRule
import com.neocat.alert.domain.entity.vo.cursor.AlertEvalCursor
import com.neocat.alert.domain.entity.vo.rule.AlertChannel
import com.neocat.alert.domain.entity.vo.rule.AlertScope
import com.neocat.alert.domain.entity.vo.rule.AlertTarget
import com.neocat.alert.domain.entity.vo.rule.Combinator
import com.neocat.alert.domain.entity.vo.rule.Comparator
import com.neocat.alert.domain.entity.vo.rule.Condition
import com.neocat.alert.domain.repository.AlertRuleRepository
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
                ['keith.ji'], [AlertChannel.EMAIL], enabled, false)
    }

    def 'new rules are disabled and do not store an activation baseline'() {
        expect:
        !rule(0L).isEnabled()
        !AlertRule.declaredFields*.name.contains('stateSince')
    }

    def 'enabling an existing rule clears prior points and starts a fresh cursor'() {
        given:
        def existing = rule()

        when:
        def updated = service.enable(1L, T)

        then:
        1 * repository.findById(1L) >> existing
        1 * repository.clearAlertEvalCursor(1L)
        1 * repository.saveAlertEvalCursor(AlertEvalCursor.empty(1L, T))
        1 * repository.save({ it.isEnabled() }) >> { args -> args[0] }
        updated.isEnabled()
    }

    def 're-enabling starts a new cursor instead of reusing previous points'() {
        given:
        def existing = rule(1L, true)

        when:
        def updated = service.enable(1L, T + 60_000L)

        then:
        1 * repository.findById(1L) >> existing
        1 * repository.clearAlertEvalCursor(1L)
        1 * repository.saveAlertEvalCursor(AlertEvalCursor.empty(1L, T + 60_000L))
        1 * repository.save({ it.isEnabled() }) >> { args -> args[0] }
        updated.isEnabled()
    }

    def 'disabling clears the evaluation cursor'() {
        when:
        def updated = service.disable(1L)

        then:
        1 * repository.findById(1L) >> rule(1L, true)
        1 * repository.clearAlertEvalCursor(1L)
        1 * repository.save({ !it.isEnabled() }) >> { args -> args[0] }
        !updated.isEnabled()
    }

    def 'editing even an enabled rule preserves ID but requires manual re-enabling'() {
        given:
        def edited = rule(0L)

        when:
        def result = service.edit(1L, new AlertRule(0L, edited.getScope(), edited.getOrgId(),
                '新名字', edited.getDescription(), edited.getTarget(), edited.getCombinator(), 5,
                edited.getConditions(), edited.getRecipients(), edited.getChannels(), true, false))

        then:
        1 * repository.findById(1L) >> rule(1L, true)
        1 * repository.clearAlertEvalCursor(1L)
        1 * repository.save({ it.getId() == 1L && it.getName() == '新名字' && it.getWindowPoints() == 5 &&
                !it.isEnabled() }) >> { args -> args[0] }
        result.getId() == 1L
        !result.isEnabled()
    }

    def 'an unknown rule is rejected before changing its window or contents'() {
        when:
        service.edit(9999L, rule(0L))

        then:
        1 * repository.findById(9999L) >> null
        0 * repository.clearAlertEvalCursor(_)
        0 * repository.save(_)
        thrown(NoSuchElementException)
    }

    def 'deletion removes both the rule and its cursor'() {
        when:
        service.delete(1L)

        then:
        1 * repository.findById(1L) >> rule()
        1 * repository.delete(1L)
        1 * repository.clearAlertEvalCursor(1L)
    }

    def 'no persisted trigger history or acknowledgement state is modeled'() {
        expect:
        AlertRuleRepository.declaredMethods*.name.every {
            !it.toLowerCase().contains('history') && !it.toLowerCase().contains('trigger')
        }
        !AlertRule.declaredFields*.name.any { it.toLowerCase() in ['acked', 'silenced', 'recovered'] }
    }
}
