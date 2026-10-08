package com.neocat.alert.domain.engine

import java.math.BigDecimal

import com.neocat.alert.domain.rule.AlertChannel
import com.neocat.alert.domain.rule.AlertRule
import com.neocat.alert.domain.rule.AlertScope
import com.neocat.alert.domain.rule.AlertTarget
import com.neocat.alert.domain.rule.Combinator
import com.neocat.alert.domain.rule.Comparator
import com.neocat.alert.domain.rule.Condition

import com.neocat.query.domain.stat.Stat
import spock.lang.Specification

import static com.neocat.alert.domain.engine.PreviewResultType.*

/**
 * G10 任务77（红）：预告警试算。
 * 对应 PRD 06 §4（保存前预告警：三态、缺数显式、不发送不落历史不改状态）、
 * §6（缺数与零值）、§12（验收 5）。
 */
class PreviewSpec extends Specification {

    static final long MIN_10_00 = 1_790_000_000_000L
    static final long MINUTE = 60_000L

    MinutePointSource source
    Map<Long, Map<Stat, BigDecimal>> pointValues
    PreviewService service

    def setup() {
        pointValues = [:]
        source = Stub(MinutePointSource) {
            values(_, _, _) >> { AlertTarget target, long minute, List<Stat> stats ->
                pointValues.getOrDefault(minute, [:])
            }
        }
        service = new PreviewService(source)
    }

    def rule(Combinator combinator, int window, List<Condition> conditions) {
        return AlertRule.draft(AlertScope.SERVICE, null, "订单失败率", "",
                AlertTarget.rawMetric("order", "TRANSACTION", "URL", "/a"),
                combinator, window, conditions, [1L], [AlertChannel.EMAIL])
    }

    def failureRateAbove(BigDecimal threshold) {
        return new Condition(Stat.FAILURE_RATE, Comparator.GT, threshold)
    }

    def hitsAbove(BigDecimal threshold) {
        return new Condition(Stat.HITS, Comparator.GT, threshold)
    }

    // ── 三态 ─────────────────────────────────────────────────

    def "窗口内所有点满足时返回「当前会触发」"() {
        given: "X=3，最近三个点都满足"
        def r = rule(Combinator.AND, 3, [failureRateAbove(0.05)])
        def t = MIN_10_00
        pointValues[t] = [(Stat.FAILURE_RATE): 0.10]
        pointValues[t - MINUTE] = [(Stat.FAILURE_RATE): 0.09]
        pointValues[t - 2 * MINUTE] = [(Stat.FAILURE_RATE): 0.08]

        when:
        def preview = service.preview(r, t)

        then:
        preview.getResult() == TRIGGER
        preview.getPoints().size() == 3
        preview.getPoints().every { it.isSatisfied() && it.isKnown() }
    }

    def "窗口内存在不满足的点时返回「当前不会触发」"() {
        given:
        def r = rule(Combinator.AND, 3, [failureRateAbove(0.05)])
        def t = MIN_10_00
        pointValues[t] = [(Stat.FAILURE_RATE): 0.10]
        pointValues[t - MINUTE] = [(Stat.FAILURE_RATE): 0.01]      // 不满足
        pointValues[t - 2 * MINUTE] = [(Stat.FAILURE_RATE): 0.08]

        when:
        def preview = service.preview(r, t)

        then:
        preview.getResult() == NO_TRIGGER
    }

    def "窗口内存在缺数点时返回「数据不足」"() {
        given: "X=3，中间一个点缺数"
        def r = rule(Combinator.AND, 3, [failureRateAbove(0.05)])
        def t = MIN_10_00
        pointValues[t] = [(Stat.FAILURE_RATE): 0.10]
        pointValues[t - MINUTE] = [(Stat.FAILURE_RATE): null]
        pointValues[t - 2 * MINUTE] = [(Stat.FAILURE_RATE): 0.08]

        when:
        def preview = service.preview(r, t)

        then:
        preview.getResult() == INSUFFICIENT_DATA
    }

    def "缺少任意一个点的数据都判定为数据不足"() {
        given: "X=3，但只提供了 2 个点"
        def r = rule(Combinator.AND, 3, [failureRateAbove(0.05)])
        def t = MIN_10_00
        pointValues[t] = [(Stat.FAILURE_RATE): 0.10]
        pointValues[t - MINUTE] = [(Stat.FAILURE_RATE): 0.09]

        when:
        def preview = service.preview(r, t)

        then:
        preview.getResult() == INSUFFICIENT_DATA
    }

    // ── 缺数不当 0 ───────────────────────────────────────────

    def "缺数点不当作 0：不会因为缺数而满足「大于阈值」"() {
        given:
        def r = rule(Combinator.AND, 1, [failureRateAbove(0.05)])
        def t = MIN_10_00
        pointValues[t] = [(Stat.FAILURE_RATE): null]

        when:
        def preview = service.preview(r, t)

        then:
        preview.getResult() == INSUFFICIENT_DATA
        preview.getPoints()[0].isKnown() == false
        preview.getPoints()[0].isSatisfied() == false
    }

    def "缺数点不会因为「低于阈值」条件而误判满足"() {
        given:
        def r = rule(Combinator.AND, 1, [new Condition(Stat.FAILURE_RATE, Comparator.LT, 0.05)])
        def t = MIN_10_00
        pointValues[t] = [(Stat.FAILURE_RATE): null]

        when:
        def preview = service.preview(r, t)

        then: "缺数既不高于也不低于，必须是数据不足"
        preview.getResult() == INSUFFICIENT_DATA
    }

    def "确认无调用的 0 是已知值，可以满足「小于等于 0」"() {
        given:
        def r = rule(Combinator.AND, 1, [new Condition(Stat.HITS, Comparator.LTE, 0.0)])
        def t = MIN_10_00
        pointValues[t] = [(Stat.HITS): 0.0]

        when:
        def preview = service.preview(r, t)

        then:
        preview.getResult() == TRIGGER
        preview.getPoints()[0].isKnown()
    }

    def "确认无调用的 0 不满足「大于 0」"() {
        given:
        def r = rule(Combinator.AND, 1, [new Condition(Stat.HITS, Comparator.GT, 0.0)])
        def t = MIN_10_00
        pointValues[t] = [(Stat.HITS): 0.0]

        when:
        def preview = service.preview(r, t)

        then:
        preview.getResult() == NO_TRIGGER
    }

    // ── AND / OR ─────────────────────────────────────────────

    def "AND：每点所有条件都满足该点才为 true"() {
        given:
        def r = rule(Combinator.AND, 1, [failureRateAbove(0.05), hitsAbove(10.0)])
        def t = MIN_10_00
        pointValues[t] = [(Stat.FAILURE_RATE): 0.10, (Stat.HITS): 100.0]

        when:
        def satisfied = service.combine(r, [(Stat.FAILURE_RATE): 0.10, (Stat.HITS): 100.0])

        then:
        satisfied

        and: "任一条件不满足则为 false"
        !service.combine(r, [(Stat.FAILURE_RATE): 0.10, (Stat.HITS): 5.0])
    }

    def "OR：每点至少一个条件满足该点才为 true"() {
        given:
        def r = rule(Combinator.OR, 1, [failureRateAbove(0.05), hitsAbove(1000.0)])

        expect:
        service.combine(r, [(Stat.FAILURE_RATE): 0.10, (Stat.HITS): 5.0])
        service.combine(r, [(Stat.FAILURE_RATE): 0.01, (Stat.HITS): 5000.0])

        and: "全部不满足才是 false"
        !service.combine(r, [(Stat.FAILURE_RATE): 0.01, (Stat.HITS): 5.0])
    }

    def "OR 下存在缺数点时整窗口判定为数据不足（缺数会打断窗口）"() {
        given: "PRD 06 §6：未知点会打断滑动窗口；§4：缺数据明确显示数据不足"
        def r = rule(Combinator.OR, 1, [failureRateAbove(0.05), hitsAbove(1000.0)])
        def t = MIN_10_00
        pointValues[t] = [(Stat.FAILURE_RATE): null, (Stat.HITS): 5000.0]

        when:
        def preview = service.preview(r, t)

        then: "即使 OR 另一个条件满足，缺数点仍使窗口不可判定"
        preview.getResult() == INSUFFICIENT_DATA
    }

    def "OR 的合并语义本身仍正确：无缺数时任一满足即满足"() {
        given:
        def r = rule(Combinator.OR, 1, [failureRateAbove(0.05), hitsAbove(1000.0)])
        def t = MIN_10_00
        pointValues[t] = [(Stat.FAILURE_RATE): 0.01, (Stat.HITS): 5000.0]

        when:
        def preview = service.preview(r, t)

        then:
        preview.getResult() == TRIGGER
    }

    def "X 属于整条规则：多条件共用同一窗口长度"() {
        given: "X=2，两个条件"
        def r = rule(Combinator.AND, 2, [failureRateAbove(0.05), hitsAbove(10.0)])
        def t = MIN_10_00
        pointValues[t] = [(Stat.FAILURE_RATE): 0.10, (Stat.HITS): 100.0]
        pointValues[t - MINUTE] = [(Stat.FAILURE_RATE): 0.09, (Stat.HITS): 90.0]

        when:
        def preview = service.preview(r, t)

        then:
        preview.getPoints().size() == 2
        preview.getResult() == TRIGGER
    }

    // ── 回看范围 ─────────────────────────────────────────────

    def "回看 X 个点：X=3 只取最近三个完整分钟点"() {
        given:
        def r = rule(Combinator.AND, 3, [failureRateAbove(0.05)])
        def t = MIN_10_00
        (0..5).each { i ->
            pointValues[t - i * MINUTE] = [(Stat.FAILURE_RATE): 0.10]
        }

        when:
        def preview = service.preview(r, t)

        then:
        preview.getPoints().size() == 3
        preview.getPoints()*.getMinute() == [t, t - MINUTE, t - 2 * MINUTE]
    }

    def "回看顺序为时间倒序（最新在前）"() {
        given:
        def r = rule(Combinator.AND, 2, [failureRateAbove(0.05)])
        def t = MIN_10_00
        pointValues[t] = [(Stat.FAILURE_RATE): 0.10]
        pointValues[t - MINUTE] = [(Stat.FAILURE_RATE): 0.10]

        when:
        def preview = service.preview(r, t)

        then:
        preview.getPoints()[0].getMinute() == t
        preview.getPoints()[1].getMinute() == t - MINUTE
    }

    def "X=1 时只看最近一个点"() {
        given:
        def r = rule(Combinator.AND, 1, [failureRateAbove(0.05)])
        def t = MIN_10_00
        pointValues[t] = [(Stat.FAILURE_RATE): 0.10]
        pointValues[t - MINUTE] = [(Stat.FAILURE_RATE): 0.01]

        when:
        def preview = service.preview(r, t)

        then:
        preview.getPoints().size() == 1
        preview.getResult() == TRIGGER
    }

    // ── 无副作用 ─────────────────────────────────────────────

    def "预告警不发送任何通知"() {
        given:
        def s = new PreviewService(source)
        def r = rule(Combinator.AND, 1, [failureRateAbove(0.05)])
        pointValues[MIN_10_00] = [(Stat.FAILURE_RATE): 0.99]

        when:
        s.preview(r, MIN_10_00)

        then:
        !PreviewService.declaredFields*.type.contains(Notifier)
    }

    def "预告警不改变规则状态"() {
        given:
        def r = rule(Combinator.AND, 1, [failureRateAbove(0.05)])
        pointValues[MIN_10_00] = [(Stat.FAILURE_RATE): 0.99]

        when:
        service.preview(r, MIN_10_00)

        then:
        !r.isEnabled()
        r.getStateSince() == null
    }

    def "预告警数据结构上不含持久化字段（不产生站内记录）"() {
        expect:
        !PreviewResult.declaredFields*.name.any { it.toLowerCase().contains("history")
                || it.toLowerCase().contains("record") }
    }

    def "无论结果如何都不抛异常"() {
        given:
        def r = rule(Combinator.AND, 2, [failureRateAbove(0.05)])

        when: "完全没有数据"
        def preview = service.preview(r, MIN_10_00)

        then:
        noExceptionThrown()
        preview.getResult() == INSUFFICIENT_DATA
    }
}
