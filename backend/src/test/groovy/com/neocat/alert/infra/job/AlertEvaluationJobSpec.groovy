package com.neocat.alert.infra.job

import spock.lang.Specification

import com.neocat.alert.config.AlertConfig
import com.neocat.alert.domain.engine.AlertEngine
import com.neocat.alert.domain.engine.AlertWindowState
import com.neocat.alert.domain.rule.*
import com.neocat.common.time.clock.TimeProvider
import com.neocat.query.domain.stat.Stat

import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

class AlertEvaluationJobSpec extends Specification {
    def cleanup() {
        TimeProvider.clock = Clock.systemUTC()
    }

    static final long T = 1_790_000_000_000L
    static final long MINUTE = 60_000L

    AlertRuleRepository repository = Mock()
    AlertEngine engine = Mock()
    AlertEvaluationJob job = new AlertEvaluationJob(repository, engine)

    static AlertRule rule(long id, int window = 1, boolean enabled = true) {
        new AlertRule(id, AlertScope.SERVICE, null, "r$id", '',
                AlertTarget.rawMetric('order', 'TRANSACTION', 'URL', '/a'),
                Combinator.AND, window,
                [new Condition(Stat.HITS, Comparator.GT, 0d)],
                [1L], [AlertChannel.EMAIL], enabled, false, T)
    }

    def 'disabled rules never reach the engine'() {
        when:
        job.evaluateAt(T)

        then:
        1 * repository.enabledRules() >> []
        0 * engine._
        job.trackedWindows() == 0
    }

    def 'enabled rule uses the persisted activation baseline and retains returned window state'() {
        given:
        def active = rule(1L, 3)
        def progressed = new AlertWindowState(1L, T, [T])

        when:
        job.evaluateAt(T)

        then:
        1 * repository.enabledRules() >> [active]
        1 * engine.onMinute(active, AlertWindowState.empty(1L, T), T) >>
                new AlertEngine.EvaluationResult(false, [], progressed)
        job.trackedWindows() == 1

        when:
        job.evaluateAt(T + MINUTE)

        then:
        1 * repository.enabledRules() >> [active]
        1 * engine.onMinute(active, progressed, T + MINUTE) >>
                new AlertEngine.EvaluationResult(false, [], progressed)
    }

    def 'one broken rule does not prevent a subsequent rule from being evaluated'() {
        given:
        def broken = rule(1L)
        def healthy = rule(2L)

        when:
        job.evaluateAt(T)

        then:
        1 * repository.enabledRules() >> [broken, healthy]
        1 * engine.onMinute(broken, _, T) >> { throw new IllegalStateException('malformed rule') }
        1 * engine.onMinute(healthy, _, T) >>
                new AlertEngine.EvaluationResult(false, [], AlertWindowState.empty(2L, T))
        job.trackedWindows() == 2
    }

    def 'reset and forget never reuse a previous activation window'() {
        when:
        job.resetWindow(1L, T + MINUTE)
        job.forget(1L)

        then:
        job.trackedWindows() == 0
    }

    def 'configured delay remains visible for operations'() {
        expect:
        job.evaluationDelaySeconds() == 5
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

    def 'changed enable baseline discards an earlier in-process window'() {
        given:
        def old = rule(1L)
        def newRule = old.withEnabled(true, T + MINUTE)

        when:
        job.evaluateAt(T)
        job.evaluateAt(T + MINUTE)

        then:
        1 * repository.enabledRules() >> [old]
        1 * repository.enabledRules() >> [newRule]
        1 * engine.onMinute(old, AlertWindowState.empty(1L, T), T) >>
                new AlertEngine.EvaluationResult(false, [], new AlertWindowState(1L, T, [T]))
        1 * engine.onMinute(newRule, AlertWindowState.empty(1L, T + MINUTE), T + MINUTE) >>
                new AlertEngine.EvaluationResult(false, [], AlertWindowState.empty(1L, T + MINUTE))
    }

    def 'a delay longer than one minute still schedules completed points'() {
        given:
        AlertConfig.EVALUATE_DELAY_SECONDS = 90
        TimeProvider.clock = Clock.fixed(Instant.parse('2026-09-24T04:02:30Z'), ZoneOffset.UTC)
        def delayed = new AlertEvaluationJob(repository, engine)

        when:
        delayed.evaluate()

        then:
        1 * repository.enabledRules() >> []
        delayed.evaluationDelaySeconds() == 90
    }
}
