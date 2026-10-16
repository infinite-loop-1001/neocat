package com.neocat.alert.application.job

import com.neocat.alert.config.AlertConfig
import com.neocat.alert.domain.AlertEngine
import com.neocat.alert.domain.entity.AlertRule
import com.neocat.alert.domain.entity.vo.cursor.AlertEvalCursor
import com.neocat.alert.domain.entity.vo.result.EvaluationResult
import com.neocat.alert.domain.entity.vo.rule.AlertChannel
import com.neocat.alert.domain.entity.vo.rule.AlertScope
import com.neocat.alert.domain.entity.vo.rule.AlertTarget
import com.neocat.alert.domain.entity.vo.rule.Combinator
import com.neocat.alert.domain.entity.vo.rule.Comparator
import com.neocat.alert.domain.entity.vo.rule.Condition
import com.neocat.alert.domain.repository.AlertRuleRepository
import com.neocat.common.time.clock.TimeProvider
import com.neocat.query.domain.stat.Stat
import spock.lang.Specification

import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

class AlertEvaluationJobSpec extends Specification {
    def cleanup() {
        TimeProvider.clock = Clock.systemUTC()
        AlertConfig.EVALUATE_DELAY_SECONDS = 5L
    }

    static final long T = 1_790_000_000_000L

    AlertRuleRepository repository = Mock()
    AlertEngine engine = Mock()
    AlertEvaluationJob job = new AlertEvaluationJob(repository, engine)

    static AlertRule rule(long id) {
        new AlertRule(id, AlertScope.SERVICE, null, "r$id", '',
                AlertTarget.rawMetric('order', 'TRANSACTION', 'URL', '/a'),
                Combinator.AND, 1,
                [new Condition(Stat.HITS, Comparator.GT, 0d)],
                ['keith.ji'], [AlertChannel.EMAIL], true, false)
    }

    def 'disabled rules never reach the engine'() {
        when:
        job.evaluate()

        then:
        1 * repository.enabledRules() >> []
        0 * engine._
    }

    def 'one broken rule does not prevent a subsequent rule from being evaluated'() {
        given:
        def broken = rule(1L)
        def healthy = rule(2L)
        def now = Instant.ofEpochMilli(T).truncatedTo(java.time.temporal.ChronoUnit.MINUTES).plusSeconds(65)
        def completed = now.minusSeconds(5).truncatedTo(java.time.temporal.ChronoUnit.MINUTES)
                .minusSeconds(60).toEpochMilli()
        TimeProvider.clock = Clock.fixed(now, ZoneOffset.UTC)

        when:
        job.evaluate()

        then:
        1 * repository.enabledRules() >> [broken, healthy]
        1 * engine.onMinute(broken, _ as AlertEvalCursor, completed) >> { throw new IllegalStateException('malformed rule') }
        1 * engine.onMinute(healthy, _ as AlertEvalCursor, completed) >>
                new EvaluationResult(false, AlertEvalCursor.empty(2L, completed))
    }

    def 'scheduler waits for the configured delay then evaluates the completed minute once'() {
        given:
        TimeProvider.clock = Clock.fixed(Instant.ofEpochMilli(T).plusSeconds(6), ZoneOffset.UTC)
        def delayed = new AlertEvaluationJob(repository, engine)

        when:
        delayed.evaluate()
        delayed.evaluate()

        then:
        1 * repository.enabledRules() >> []
    }

    def 'a delay longer than one minute still schedules the completed point once'() {
        given:
        AlertConfig.EVALUATE_DELAY_SECONDS = 90L
        TimeProvider.clock = Clock.fixed(Instant.parse('2026-09-24T04:02:30Z'), ZoneOffset.UTC)
        def delayed = new AlertEvaluationJob(repository, engine)

        when:
        delayed.evaluate()
        delayed.evaluate()

        then:
        1 * repository.enabledRules() >> []
    }
}
