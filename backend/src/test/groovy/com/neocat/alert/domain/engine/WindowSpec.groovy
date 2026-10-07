package com.neocat.alert.domain.engine

import com.neocat.alert.domain.rule.AlertChannel
import com.neocat.alert.domain.rule.AlertRule
import com.neocat.alert.domain.rule.AlertTarget
import com.neocat.alert.domain.rule.Combinator
import com.neocat.alert.domain.rule.Comparator
import com.neocat.alert.domain.rule.Condition

import com.neocat.query.domain.stat.Stat
import spock.lang.Specification

import static com.neocat.alert.domain.rule.AlertScope.SERVICE

/**
 * G10 任务79（红）：滑动窗口与分钟判定。
 * 对应 PRD 06 §3.2（启用：只使用启用后的完整分钟点）、§5（滑动窗口触发、
 * X=3 第 3 点触发、持续满足每分钟再触发、缺数打断、迟到不回溯）、§6（缺数与零值）、
 * §12（验收 2、3、6）。
 */
class WindowSpec extends Specification {

    static final long T = 1_790_000_000_000L
    static final long MINUTE = 60_000L

    MinutePointSource source
    Map<Long, Map<Stat, Double>> pointValues
    Notifier notifier
    List<AlertNotification> sent
    AlertEngine engine

    def setup() {
        pointValues = [:]
        source = Stub(MinutePointSource) {
            values(_, _, _) >> { AlertTarget target, long minute, List<Stat> stats ->
                pointValues.getOrDefault(minute, [:])
            }
        }
        sent = []
        notifier = Mock(Notifier) {
            send(_) >> { AlertNotification notification -> sent.add(notification) }
        }
        engine = new AlertEngine(source, notifier)
    }

    def rule(int window, Combinator combinator = Combinator.AND,
             List<Condition> conditions = null, List<Long> recipients = [1L]) {
        return new AlertRule(1L, SERVICE, null, "订单失败率", "",
                AlertTarget.rawMetric("order", "TRANSACTION", "URL", "/a"),
                combinator, window,
                conditions == null ? [new Condition(Stat.FAILURE_RATE, Comparator.GT, 0.05d)] : conditions,
                recipients, [AlertChannel.EMAIL], true, false, T)
    }

    def state(long baseline = T) {
        return AlertWindowState.empty(1L, baseline)
    }

    def putSatisfied(long minute) {
        pointValues[minute] = [(Stat.FAILURE_RATE): 0.10d]
    }

    def putUnsatisfied(long minute) {
        pointValues[minute] = [(Stat.FAILURE_RATE): 0.01d]
    }

    // ── X=3 的触发点 ─────────────────────────────────────────

    def "X=3 时第 3 个满足点触发"() {
        given:
        def r = rule(3)
        def s = state()
        putSatisfied(T)
        putSatisfied(T + MINUTE)
        putSatisfied(T + 2 * MINUTE)

        when: "依次到达三个点"
        def r1 = engine.onMinute(r, s, T)
        def r2 = engine.onMinute(r, r1.getState(), T + MINUTE)
        def r3 = engine.onMinute(r, r2.getState(), T + 2 * MINUTE)

        then:
        !r1.isTriggered()
        !r2.isTriggered()
        r3.isTriggered()
        sent.size() == 1
    }

    def "X=3 时前两个点不触发（窗口未满）"() {
        given:
        def r = rule(3)
        putSatisfied(T)
        putSatisfied(T + MINUTE)

        when:
        def r1 = engine.onMinute(r, state(), T)
        def r2 = engine.onMinute(r, r1.getState(), T + MINUTE)

        then:
        !r1.isTriggered()
        !r2.isTriggered()
        sent.isEmpty()
    }

    def "持续满足时每分钟都可能再次触发"() {
        given: "X=3，连续 5 个满足点"
        def r = rule(3)
        def s = state()
        (0..4).each { putSatisfied(T + it * MINUTE) }

        when:
        def results = []
        (0..4).each { i ->
            def res = engine.onMinute(r, s, T + i * MINUTE)
            s = res.getState()
            results << res.isTriggered()
        }

        then: "第 3、4、5 点各触发一次"
        results == [false, false, true, true, true]
        sent.size() == 3
    }

    def "窗口内有一个不满足点则不触发"() {
        given: "X=3，中间点不满足"
        def r = rule(3)
        def s = state()
        putSatisfied(T)
        putUnsatisfied(T + MINUTE)
        putSatisfied(T + 2 * MINUTE)

        when:
        def r1 = engine.onMinute(r, s, T)
        def r2 = engine.onMinute(r, r1.getState(), T + MINUTE)
        def r3 = engine.onMinute(r, r2.getState(), T + 2 * MINUTE)

        then:
        !r1.isTriggered() && !r2.isTriggered() && !r3.isTriggered()
        sent.isEmpty()
    }

    def "窗口滑动后旧的不满足点退出：随后的 X 点可再次触发"() {
        given: "X=2；第 1 点不满足，第 2、3 点满足"
        def r = rule(2)
        def s = state()
        putUnsatisfied(T)
        putSatisfied(T + MINUTE)
        putSatisfied(T + 2 * MINUTE)

        when:
        def r1 = engine.onMinute(r, s, T)
        def r2 = engine.onMinute(r, r1.getState(), T + MINUTE)
        def r3 = engine.onMinute(r, r2.getState(), T + 2 * MINUTE)

        then:
        !r1.isTriggered()
        !r2.isTriggered()          // 窗口含 T（不满足）
        r3.isTriggered()           // 窗口 [T+1, T+2] 全满足
    }

    // ── 启用基线：最重要的边界 ───────────────────────────────

    def "启用前的历史点不得用于填充窗口"() {
        given: "启用前 T-3..T-1 都已满足，启用时刻为 T"
        def r = rule(3)
        (1..3).each { putSatisfied(T - it * MINUTE) }
        putSatisfied(T)
        def s = AlertWindowState.empty(1L, T)     // 基线 = 启用时刻 T

        when: "启用后第一个完整点 T 到达"
        def res = engine.onMinute(r, s, T)

        then: "历史 3 个满足点不被使用，窗口只有 1 个点，不触发"
        !res.isTriggered()
        res.getState().getPoints() == [T]
    }

    def "启用后需累积到 X 个点才可能首次触发"() {
        given: "启用前已有大量满足点"
        def r = rule(3)
        (1..10).each { putSatisfied(T - it * MINUTE) }
        def s = AlertWindowState.empty(1L, T)
        (0..2).each { putSatisfied(T + it * MINUTE) }

        when:
        def r1 = engine.onMinute(r, s, T)
        def r2 = engine.onMinute(r, r1.getState(), T + MINUTE)
        def r3 = engine.onMinute(r, r2.getState(), T + 2 * MINUTE)

        then: "启用后第 3 个点才触发"
        !r1.isTriggered()
        !r2.isTriggered()
        r3.isTriggered()
    }

    def "早于基线的迟到点被忽略，不进入窗口"() {
        given:
        def r = rule(2)
        def s = AlertWindowState.empty(1L, T + MINUTE)   // 基线为 T+1
        putSatisfied(T)                                   // 早于基线
        putSatisfied(T + MINUTE)

        when:
        def res = engine.onMinute(r, s, T)

        then: "早于基线的点不进入窗口"
        !res.getState().getPoints().contains(T)
        !res.isTriggered()
    }

    def "补人后从补充时刻重建窗口：不追溯旧异常"() {
        given: "X=2；补人前 T..T+2 都满足，补人时刻为 T+3"
        def r = rule(2)
        (0..2).each { putSatisfied(T + it * MINUTE) }
        def afterReplenish = AlertWindowState.empty(1L, T + 3 * MINUTE)
        putSatisfied(T + 3 * MINUTE)
        putSatisfied(T + 4 * MINUTE)

        when: "补人后第一个点到达"
        def r1 = engine.onMinute(r, afterReplenish, T + 3 * MINUTE)

        then: "不因补人前的满足点而立即触发"
        !r1.isTriggered()
        r1.getState().getPoints() == [T + 3 * MINUTE]

        when: "补人后第 2 个点到达"
        def r2 = engine.onMinute(r, r1.getState(), T + 4 * MINUTE)

        then:
        r2.isTriggered()
    }

    // ── 缺数打断 ─────────────────────────────────────────────

    def "缺数点使包含它的窗口不满足"() {
        given: "X=2；T 缺数，T+1 满足"
        def r = rule(2)
        putSatisfied(T + MINUTE)     // T 未提供 -> 缺数
        def s = state()

        when:
        def r1 = engine.onMinute(r, s, T)
        def r2 = engine.onMinute(r, r1.getState(), T + MINUTE)

        then:
        !r1.isTriggered()
        !r2.isTriggered()
    }

    def "缺数不当作 0：不会因为缺数而满足「小于阈值」"() {
        given:
        def r = rule(1, Combinator.AND, [new Condition(Stat.FAILURE_RATE, Comparator.LT, 0.05d)])
        // T 未提供 -> 缺数

        when:
        def res = engine.onMinute(r, state(), T)

        then:
        !res.isTriggered()
    }

    def "确认无调用的 0 是已知值，可以满足条件"() {
        given:
        def r = rule(1, Combinator.AND, [new Condition(Stat.HITS, Comparator.LTE, 0d)])
        pointValues[T] = [(Stat.HITS): 0.0d]

        when:
        def res = engine.onMinute(r, state(), T)

        then:
        res.isTriggered()
    }

    def "缺数点之后窗口恢复：后续 X 个满足点可再次触发"() {
        given: "X=2；T+1 缺数，T+2、T+3 满足"
        def r = rule(2)
        def s = state()
        putSatisfied(T + 2 * MINUTE)
        putSatisfied(T + 3 * MINUTE)

        when:
        def r1 = engine.onMinute(r, s, T + MINUTE)
        def r2 = engine.onMinute(r, r1.getState(), T + 2 * MINUTE)
        def r3 = engine.onMinute(r, r2.getState(), T + 3 * MINUTE)

        then:
        !r1.isTriggered()
        !r2.isTriggered()          // 窗口含缺数点
        r3.isTriggered()           // 窗口 [T+2, T+3] 全满足
    }

    // ── 多条件与 AND/OR ──────────────────────────────────────

    def "AND：窗口内每点都需全部条件满足"() {
        given:
        def r = rule(2, Combinator.AND,
                [new Condition(Stat.FAILURE_RATE, Comparator.GT, 0.05d),
                 new Condition(Stat.HITS, Comparator.GT, 10d)])
        pointValues[T] = [(Stat.FAILURE_RATE): 0.10d, (Stat.HITS): 100d]
        pointValues[T + MINUTE] = [(Stat.FAILURE_RATE): 0.10d, (Stat.HITS): 5d]   // hits 不满足

        when:
        def r1 = engine.onMinute(r, state(), T)
        def r2 = engine.onMinute(r, r1.getState(), T + MINUTE)

        then:
        !r1.isTriggered() && !r2.isTriggered()
    }

    def "OR：窗口内每点至少一个条件满足即可"() {
        given:
        def r = rule(2, Combinator.OR,
                [new Condition(Stat.FAILURE_RATE, Comparator.GT, 0.05d),
                 new Condition(Stat.HITS, Comparator.GT, 1000d)])
        pointValues[T] = [(Stat.FAILURE_RATE): 0.10d, (Stat.HITS): 5d]
        pointValues[T + MINUTE] = [(Stat.FAILURE_RATE): 0.01d, (Stat.HITS): 5000d]

        when:
        def r1 = engine.onMinute(r, state(), T)
        def r2 = engine.onMinute(r, r1.getState(), T + MINUTE)

        then:
        !r1.isTriggered()
        r2.isTriggered()
    }

    def "X 属于整条规则：多条件共用同一窗口"() {
        given:
        def r = rule(3, Combinator.AND,
                [new Condition(Stat.FAILURE_RATE, Comparator.GT, 0.05d),
                 new Condition(Stat.HITS, Comparator.GT, 10d)])
        (0..2).each { i ->
            pointValues[T + i * MINUTE] = [(Stat.FAILURE_RATE): 0.10d, (Stat.HITS): 100d]
        }

        when:
        def s = state()
        def results = (0..2).collect { i ->
            def res = engine.onMinute(r, s, T + i * MINUTE)
            s = res.getState()
            res.isTriggered()
        }

        then:
        results == [false, false, true]
    }

    // ── 无收件人 ─────────────────────────────────────────────

    def "无有效收件人时仍评估但不发送"() {
        given:
        def r = rule(1, Combinator.AND, null, [])
        putSatisfied(T)

        when:
        def res = engine.onMinute(r, state(), T)

        then:
        res.isTriggered()
        res.getNotifications().isEmpty()
        sent.isEmpty()
    }

    // ── 不落历史 ─────────────────────────────────────────────

    def "触发不产生站内历史：判定结果只有触发标志与通知"() {
        given:
        def r = rule(1)
        putSatisfied(T)

        when:
        def res = engine.onMinute(r, state(), T)

        then:
        res.isTriggered()
        and: "EvaluationResult 不含历史记录字段"
        !AlertEngine.EvaluationResult.declaredFields*.name.any {
            it.toLowerCase().contains("history") || it.toLowerCase().contains("record")
        }
    }

    def "未触发时不发送任何通知"() {
        given:
        def r = rule(2)
        putSatisfied(T)

        when:
        engine.onMinute(r, state(), T)

        then:
        sent.isEmpty()
    }

    def "通知内容包含规则名与触发点"() {
        given:
        def r = rule(1)
        putSatisfied(T)

        when:
        engine.onMinute(r, state(), T)

        then:
        def n = sent[0]
        n.getRuleId() == 1L
        n.getRuleName() == "订单失败率"
        n.getRecipients() == [1L]
        n.getChannel() == AlertChannel.EMAIL
        n.getTriggeredAt() == T
    }

    def "多个通道各发送一次"() {
        given:
        def r = new AlertRule(1L, SERVICE, null, "r", "",
                AlertTarget.rawMetric("order", "TRANSACTION", "URL", "/a"),
                Combinator.AND, 1, [new Condition(Stat.FAILURE_RATE, Comparator.GT, 0.05d)],
                [1L], [AlertChannel.EMAIL, AlertChannel.DINGTALK, AlertChannel.FEISHU], true, false, T)
        putSatisfied(T)

        when:
        engine.onMinute(r, state(), T)

        then:
        sent.size() == 3
        sent*.getChannel() as Set == [AlertChannel.EMAIL, AlertChannel.DINGTALK, AlertChannel.FEISHU] as Set
    }

    def "迟到数据不回溯已经完成的判定"() {
        given: "X=1；T 先被判为不满足（有值但不满足）"
        def r = rule(1)
        putUnsatisfied(T)
        def res = engine.onMinute(r, state(), T)

        expect:
        !res.isTriggered()

        when: "随后 T 的值被迟到数据更新为满足"
        putSatisfied(T)

        then: "已完成的判定不被回溯改写"
        !res.isTriggered()
        res.getState().getPoints() == [T]
    }

    // ── 窗口上限 ─────────────────────────────────────────────

    def "窗口只保留最近 X 个点，避免无限增长"() {
        given:
        def r = rule(3)
        def s = state()
        (0..9).each { putSatisfied(T + it * MINUTE) }

        when:
        (0..9).each { i ->
            s = engine.onMinute(r, s, T + i * MINUTE).getState()
        }

        then:
        s.getPoints().size() == 3
        s.getPoints() == [T + 7 * MINUTE, T + 8 * MINUTE, T + 9 * MINUTE]
    }
}
