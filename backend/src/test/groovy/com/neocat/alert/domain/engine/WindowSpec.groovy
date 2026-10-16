package com.neocat.alert.domain.engine

import com.neocat.alert.domain.AlertEngine
import com.neocat.alert.domain.entity.AlertRule
import com.neocat.alert.domain.entity.vo.cursor.AlertEvalCursor
import com.neocat.alert.domain.entity.vo.result.EvaluationResult
import com.neocat.alert.domain.entity.vo.rule.AlertTarget
import com.neocat.alert.domain.entity.vo.rule.Combinator
import com.neocat.alert.domain.entity.vo.rule.Comparator
import com.neocat.alert.domain.entity.vo.rule.Condition
import com.neocat.alert.domain.entity.vo.source.MinutePoint
import com.neocat.alert.domain.event.AlertTriggeredEvent
import com.neocat.alert.domain.gateway.MinutePointSourceGateway
import com.neocat.query.domain.stat.Stat
import org.springframework.context.ApplicationEventPublisher
import spock.lang.Specification

import static com.neocat.alert.domain.entity.vo.rule.AlertScope.SERVICE

/**
 * 滑动窗口与分钟判定。
 *
 * <p>每次判定都以当前分钟为终点，向数据源读取连续 X 个点。
 * 早于基线的当前分钟直接忽略，游标停在调用方传入的位置。
 */
class WindowSpec extends Specification {

    static final long MINUTE = 60_000L
    static final long T = 1_790_000_040_000L

    MinutePointSourceGateway source
    Map<Long, Map<Stat, BigDecimal>> pointValues
    List<AlertTriggeredEvent> published
    AlertEngine engine

    def setup() {
        pointValues = [:]
        source = Stub(MinutePointSourceGateway) {
            batchMinuteValues(_, _, _) >> { AlertTarget target, List<Long> minutes, List<Stat> stats ->
                minutes.findResults { minute ->
                    def values = pointValues[minute]
                    values == null ? null : new MinutePoint(minute, values)
                }
            }
        }
        published = []
        def publisher = Stub(ApplicationEventPublisher) {
            publishEvent(_) >> { AlertTriggeredEvent event -> published.add(event) }
        }
        engine = new AlertEngine(source, publisher)
    }

    def rule(int window, Combinator combinator = Combinator.AND,
             List<Condition> conditions = null, List<String> recipients = ['keith.ji']) {
        return new AlertRule(1L, SERVICE, null, '订单失败率', '',
                AlertTarget.rawMetric('order', 'TRANSACTION', 'URL', '/a'),
                combinator, window,
                conditions == null ? [new Condition(Stat.FAILURE_RATE, Comparator.GT, 0.05)] : conditions,
                recipients, [], true, false)
    }

    def cursor(long baseline = T - 10 * MINUTE) {
        return AlertEvalCursor.empty(1L, baseline)
    }

    def putSatisfied(long minute) {
        pointValues[minute] = [(Stat.FAILURE_RATE): 0.10]
    }

    def putUnsatisfied(long minute) {
        pointValues[minute] = [(Stat.FAILURE_RATE): 0.01]
    }

    def '实时判定按窗口向数据源一次读取去重后的统计项'() {
        given:
        def cardTarget = new AlertTarget(null, 12L, 'order', 'TRANSACTION', 'URL', '/a', null,
                [Stat.AVG, null, Stat.HITS, Stat.AVG])
        def r = new AlertRule(1L, SERVICE, null, 'r', '', cardTarget, Combinator.AND, 1,
                [new Condition(Stat.HITS, Comparator.GT, 1G),
                 new Condition(Stat.FAILURE_RATE, Comparator.GT, 0.05G)],
                [], [], true, false)
        def requested = []
        def realTimeSource = Stub(MinutePointSourceGateway) {
            batchMinuteValues(cardTarget, _, _) >> { AlertTarget target, List<Long> minutes, List<Stat> stats ->
                requested.add([minutes, stats])
                [new MinutePoint(T, [(Stat.AVG): 10.0G, (Stat.HITS): 10.0G, (Stat.FAILURE_RATE): 0.1G])]
            }
        }
        def realTimeEngine = new AlertEngine(realTimeSource, Stub(ApplicationEventPublisher))

        when:
        def res = realTimeEngine.onMinute(r, cursor(), T)

        then:
        res.isTriggered()
        requested == [[[T], [Stat.AVG, Stat.HITS, Stat.FAILURE_RATE]]]
    }

    def 'X=3 时窗口未满三个满足点不触发，第三个点触发'() {
        given:
        def r = rule(3)
        putSatisfied(T)
        putSatisfied(T + MINUTE)

        when:
        def partial = engine.onMinute(r, cursor(), T + MINUTE)
        putSatisfied(T + 2 * MINUTE)
        def full = engine.onMinute(r, cursor(), T + 2 * MINUTE)

        then:
        !partial.isTriggered()
        full.isTriggered()
        full.getCursor().getLastEvaluatedMinute() == T + 2 * MINUTE
        published.size() == 1
    }

    def '持续满足时每个完整窗口都再次触发'() {
        given:
        def r = rule(3)
        (0..4).each { putSatisfied(T + it * MINUTE) }

        when:
        def results = (2..4).collect { i ->
            engine.onMinute(r, cursor(), T + i * MINUTE).isTriggered()
        }

        then:
        results == [true, true, true]
        published.size() == 3
    }

    def '窗口内有一个不满足点则不触发'() {
        given:
        def r = rule(3)
        putSatisfied(T)
        putUnsatisfied(T + MINUTE)
        putSatisfied(T + 2 * MINUTE)

        when:
        def res = engine.onMinute(r, cursor(), T + 2 * MINUTE)

        then:
        !res.isTriggered()
        published.isEmpty()
    }

    def '窗口滑出旧的不满足点后随后的 X 点可再次触发'() {
        given:
        def r = rule(2)
        putUnsatisfied(T)
        putSatisfied(T + MINUTE)
        putSatisfied(T + 2 * MINUTE)

        when:
        def blocked = engine.onMinute(r, cursor(), T + MINUTE)
        def recovered = engine.onMinute(r, cursor(), T + 2 * MINUTE)

        then:
        !blocked.isTriggered()
        recovered.isTriggered()
    }

    def '当前实现仍用基线之前的历史点填满窗口'() {
        given:
        def r = rule(3)
        (1..3).each { putSatisfied(T - it * MINUTE) }
        putSatisfied(T)

        when:
        def res = engine.onMinute(r, AlertEvalCursor.empty(1L, T), T)

        then:
        res.isTriggered()
        published.size() == 1
    }

    def '启用当分钟即可用基线之前的满足点触发'() {
        given:
        def r = rule(3)
        (1..10).each { putSatisfied(T - it * MINUTE) }
        (0..2).each { putSatisfied(T + it * MINUTE) }

        when:
        def first = engine.onMinute(r, AlertEvalCursor.empty(1L, T), T)

        then:
        first.isTriggered()
    }

    def '早于基线的分钟被忽略且不推进游标'() {
        given:
        def r = rule(2)
        def before = AlertEvalCursor.empty(1L, T + MINUTE)
        putSatisfied(T)

        when:
        def res = engine.onMinute(r, before, T)

        then:
        !res.isTriggered()
        res.getCursor().is(before)
        res.getCursor().getLastEvaluatedMinute() == null
        published.isEmpty()
    }

    def '补人当分钟仍会用基线之前的满足点触发'() {
        given:
        def r = rule(2)
        (0..2).each { putSatisfied(T + it * MINUTE) }
        def afterReplenish = AlertEvalCursor.empty(1L, T + 3 * MINUTE)
        putSatisfied(T + 3 * MINUTE)

        when:
        def first = engine.onMinute(r, afterReplenish, T + 3 * MINUTE)

        then:
        first.isTriggered()
    }

    def '缺数点使包含它的窗口不满足'() {
        given:
        def r = rule(2)
        putSatisfied(T + MINUTE)

        when:
        def res = engine.onMinute(r, cursor(), T + MINUTE)

        then:
        !res.isTriggered()
    }

    def '缺数不当作 0，不会因为缺数而满足小于阈值'() {
        given:
        def r = rule(1, Combinator.AND, [new Condition(Stat.FAILURE_RATE, Comparator.LT, 0.05)])

        when:
        def res = engine.onMinute(r, cursor(), T)

        then:
        !res.isTriggered()
    }

    def '确认无调用的 0 是已知值，可以满足条件'() {
        given:
        def r = rule(1, Combinator.AND, [new Condition(Stat.HITS, Comparator.LTE, 0.0)])
        pointValues[T] = [(Stat.HITS): 0.0]

        when:
        def res = engine.onMinute(r, cursor(), T)

        then:
        res.isTriggered()
    }

    def '缺数点滑出窗口后后续 X 个满足点可再次触发'() {
        given:
        def r = rule(2)
        putSatisfied(T + 2 * MINUTE)
        putSatisfied(T + 3 * MINUTE)

        when:
        def blocked = engine.onMinute(r, cursor(), T + 2 * MINUTE)
        def recovered = engine.onMinute(r, cursor(), T + 3 * MINUTE)

        then:
        !blocked.isTriggered()
        recovered.isTriggered()
    }

    def 'AND 要求窗口内每点都全部条件满足'() {
        given:
        def r = rule(2, Combinator.AND,
                [new Condition(Stat.FAILURE_RATE, Comparator.GT, 0.05),
                 new Condition(Stat.HITS, Comparator.GT, 10.0)])
        pointValues[T] = [(Stat.FAILURE_RATE): 0.10, (Stat.HITS): 100.0]
        pointValues[T + MINUTE] = [(Stat.FAILURE_RATE): 0.10, (Stat.HITS): 5.0]

        when:
        def res = engine.onMinute(r, cursor(), T + MINUTE)

        then:
        !res.isTriggered()
    }

    def 'OR 要求窗口内每点至少一个条件满足'() {
        given:
        def r = rule(2, Combinator.OR,
                [new Condition(Stat.FAILURE_RATE, Comparator.GT, 0.05),
                 new Condition(Stat.HITS, Comparator.GT, 1000.0)])
        pointValues[T] = [(Stat.FAILURE_RATE): 0.10, (Stat.HITS): 5.0]
        pointValues[T + MINUTE] = [(Stat.FAILURE_RATE): 0.01, (Stat.HITS): 5000.0]

        when:
        def res = engine.onMinute(r, cursor(), T + MINUTE)

        then:
        res.isTriggered()
    }

    def 'X 属于整条规则，多条件共用同一窗口'() {
        given:
        def r = rule(3, Combinator.AND,
                [new Condition(Stat.FAILURE_RATE, Comparator.GT, 0.05),
                 new Condition(Stat.HITS, Comparator.GT, 10.0)])
        (0..2).each { i ->
            pointValues[T + i * MINUTE] = [(Stat.FAILURE_RATE): 0.10, (Stat.HITS): 100.0]
        }

        when:
        def res = engine.onMinute(r, cursor(), T + 2 * MINUTE)

        then:
        res.isTriggered()
    }

    def '无有效收件人时仍评估并发布事件'() {
        given:
        def r = rule(1, Combinator.AND, null, [])
        putSatisfied(T)

        when:
        def res = engine.onMinute(r, cursor(), T)

        then:
        res.isTriggered()
        published.size() == 1
        published[0].getRecipients().isEmpty()
    }

    def '触发不产生站内历史，判定结果只有触发标志与游标'() {
        given:
        def r = rule(1)
        putSatisfied(T)

        when:
        def res = engine.onMinute(r, cursor(), T)

        then:
        res.isTriggered()
        !EvaluationResult.declaredFields*.name.any {
            it.toLowerCase().contains('history') || it.toLowerCase().contains('record')
        }
    }

    def '未触发时不发布事件'() {
        given:
        def r = rule(2)
        putSatisfied(T)

        when:
        engine.onMinute(r, cursor(), T)

        then:
        published.isEmpty()
    }

    def '触发事件包含规则名、收件人与触发点'() {
        given:
        def r = rule(1)
        putSatisfied(T)

        when:
        engine.onMinute(r, cursor(), T)

        then:
        def event = published[0]
        event.getRuleId() == 1L
        event.getRuleName() == '订单失败率'
        event.getRecipients() == ['keith.ji']
        event.getTriggeredAt() == T
        event.getTriggerPoints()*.getMinute() == [T]
    }

    def '已返回的不满足结果不会因为随后改写数据源而被改写'() {
        given:
        def r = rule(1)
        putUnsatisfied(T)
        def res = engine.onMinute(r, cursor(), T)

        expect:
        !res.isTriggered()

        when:
        putSatisfied(T)

        then:
        !res.isTriggered()
        res.getCursor().getLastEvaluatedMinute() == T
    }
}
