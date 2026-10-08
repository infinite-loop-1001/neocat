package com.neocat.dashboard.domain.card

import java.math.BigDecimal

import com.neocat.dashboard.domain.formula.FormulaParser

import com.neocat.query.domain.stat.Stat
import spock.lang.Specification
import spock.lang.Unroll

/**
 * G9 任务69（红）：卡片目标与桶对齐求值。
 * 对应 PRD 05 §3（卡片目标范围：一个服务 + 一个指标对象）、
 * §4（同一指标多统计项聚合与四则运算）、§5（时间对齐与缺数：不把缺数当 0、
 * 不沿用上一点、除零不可计算）、§11（验收 3、5）。
 */
class CardTargetSpec extends Specification {

    CardEvaluator evaluator = new CardEvaluator()
    FormulaParser parser = new FormulaParser()

    def formula(String expr) {
        def r = parser.parse(expr)
        assert r.valid(): "测试固件公式应可解析：$expr"
        return r.getFormula()
    }

    // ── 卡片目标范围 ─────────────────────────────────────────

    def "合法卡片：一个服务 + 一个指标对象"() {
        given:
        def card = card("order", "TRANSACTION", "URL", "POST /orders", null, "failures / hits")

        expect:
        evaluator.validateTarget(card) == null
    }

    def "Metric 卡片带标签组合也是合法目标"() {
        given:
        def card = card("order", "METRIC", "order.amount", null, "city=上海;", "avg(qps)")

        expect:
        evaluator.validateTarget(card) == null
    }

    def "缺少服务名时卡片非法"() {
        given:
        def card = card(null, "TRANSACTION", "URL", "/a", null, "hits")

        expect:
        evaluator.validateTarget(card) == "INVALID_TARGET"
    }

    def "缺少指标对象（type 与 name 都为空）时卡片非法"() {
        given:
        def card = card("order", "TRANSACTION", null, null, null, "hits")

        expect:
        evaluator.validateTarget(card) == "INVALID_TARGET"
    }

    def "缺少指标类型时卡片非法"() {
        given:
        def card = card("order", null, "URL", "/a", null, "hits")

        expect:
        evaluator.validateTarget(card) == "INVALID_TARGET"
    }

    def "公式不合法时卡片非法"() {
        given:
        def card = card("order", "TRANSACTION", "URL", "/a", null, "hits + tp99")

        expect:
        evaluator.validateTarget(card) == "UNIT_MISMATCH"
    }

    // ── 单桶求值 ─────────────────────────────────────────────

    def "简单除法求值：failures / hits"() {
        when:
        def point = evaluator.evaluate(formula("failures / hits"),
                [(Stat.HITS): 100.0, (Stat.FAILURES): 25.0], 1000L, 2000L)

        then:
        point.getValue() == 0.25
        point.getOutcome() == CardPointOutcome.OK
        point.getMissingInputs().isEmpty()
    }

    def "减法求值：tp99 - avgDuration"() {
        when:
        def point = evaluator.evaluate(formula("tp99 - avgDuration"),
                [(Stat.TP99): 200.0, (Stat.AVG): 50.0], 1000L, 2000L)

        then:
        point.getValue() == 150.0
    }

    def "常数缩放"() {
        when:
        def point = evaluator.evaluate(formula("hits * 100"),
                [(Stat.HITS): 3.0], 1000L, 2000L)

        then:
        point.getValue() == 300.0
    }

    def "聚合函数包裹单个统计项"() {
        when:
        def point = evaluator.evaluate(formula("sum(hits)"),
                [(Stat.HITS): 42.0], 1000L, 2000L)

        then:
        point.getValue() == 42.0
    }

    def "括号改变求值顺序"() {
        when: "(tp99 - avgDuration) / 2"
        def point = evaluator.evaluate(formula("(tp99 - avgDuration) / 2"),
                [(Stat.TP99): 200.0, (Stat.AVG): 100.0], 1000L, 2000L)

        then:
        point.getValue() == 50.0
    }

    // ── 缺数：不把缺数当 0 ───────────────────────────────────

    def "任一输入缺数时结果为缺口"() {
        when: "hits 缺数"
        def point = evaluator.evaluate(formula("failures / hits"),
                [(Stat.HITS): null, (Stat.FAILURES): 25.0], 1000L, 2000L)

        then:
        point.getValue() == null
        point.isGap()
        point.getMissingInputs() == ["hits"]
    }

    def "缺数不当作 0：failures / null 不返回 0 或无穷"() {
        when:
        def point = evaluator.evaluate(formula("failures / hits"),
                [(Stat.HITS): null, (Stat.FAILURES): 25.0], 1000L, 2000L)

        then:
        point.getValue() == null
        point.getValue() != 0.0
        point.getOutcome() != CardPointOutcome.OK
    }

    def "多个输入缺数时全部列出"() {
        when:
        def point = evaluator.evaluate(formula("tp99 - avgDuration"),
                [(Stat.TP99): null, (Stat.AVG): null], 1000L, 2000L)

        then:
        point.isGap()
        point.getMissingInputs() as Set == ["tp99", "avgDuration"] as Set
    }

    def "缺口点不沿用上一个点的值"() {
        given: "连续两个桶：第一个有值，第二个缺数"
        def inputs1 = [(Stat.HITS): 100.0, (Stat.FAILURES): 25.0]
        def inputs2 = [(Stat.HITS): null, (Stat.FAILURES): 25.0]

        when:
        def p1 = evaluator.evaluate(formula("failures / hits"), inputs1, 1000L, 2000L)
        def p2 = evaluator.evaluate(formula("failures / hits"), inputs2, 2000L, 3000L)

        then:
        p1.getValue() == 0.25
        p2.getValue() == null
        p2.getValue() != p1.getValue()
        p2.isGap()
    }

    def "缺口点保留桶边界信息，供前端画断点"() {
        when:
        def point = evaluator.evaluate(formula("hits"), [(Stat.HITS): null], 5000L, 6000L)

        then:
        point.getBucketStart() == 5000L
        point.getBucketEnd() == 6000L
        point.isGap()
    }

    def "输入值全部存在时不是缺口"() {
        when:
        def point = evaluator.evaluate(formula("hits"),
                [(Stat.HITS): 0.0], 1000L, 2000L)

        then: "确认无调用的 0 是有效值，不是缺口"
        point.getValue() == 0.0
        point.getOutcome() == CardPointOutcome.OK
        !point.isGap()
    }

    // ── 除零 ─────────────────────────────────────────────────

    def "除数为 0 时该点不可计算"() {
        when:
        def point = evaluator.evaluate(formula("failures / hits"),
                [(Stat.HITS): 0.0, (Stat.FAILURES): 0.0], 1000L, 2000L)

        then:
        point.getValue() == null
        point.isUndefined()
        point.getOutcome() == CardPointOutcome.DIVIDE_BY_ZERO
        !point.isGap()
    }

    def "除零不是缺口：缺口表示缺数据，除零表示可计算但无定义"() {
        given:
        def zeroDivisor = evaluator.evaluate(formula("failures / hits"),
                [(Stat.HITS): 0.0, (Stat.FAILURES): 5.0], 1000L, 2000L)
        def gap = evaluator.evaluate(formula("failures / hits"),
                [(Stat.HITS): null, (Stat.FAILURES): 5.0], 1000L, 2000L)

        expect:
        zeroDivisor.getOutcome() == CardPointOutcome.DIVIDE_BY_ZERO
        gap.getOutcome() == CardPointOutcome.GAP
        zeroDivisor.getOutcome() != gap.getOutcome()
    }

    def "嵌套除法中任一层除零都判为不可计算"() {
        when: "(tp99 - avgDuration) / (hits - hits)"
        def point = evaluator.evaluate(formula("(tp99 - avgDuration) / (hits - hits)"),
                [(Stat.TP99): 200.0, (Stat.AVG): 100.0, (Stat.HITS): 5.0], 1000L, 2000L)

        then:
        point.isUndefined()
        point.getValue() == null
    }

    def "常数为除数且非零时正常求值"() {
        when:
        def point = evaluator.evaluate(formula("hits / 2"),
                [(Stat.HITS): 10.0], 1000L, 2000L)

        then:
        point.getValue() == 5.0
        point.getOutcome() == CardPointOutcome.OK
    }

    def "缺数优先于除零：输入缺失时不报告除零"() {
        when: "hits 缺数，failures 为 5"
        def point = evaluator.evaluate(formula("failures / hits"),
                [(Stat.HITS): null, (Stat.FAILURES): 5.0], 1000L, 2000L)

        then: "先判缺数"
        point.isGap()
        !point.isUndefined()
    }

    // ── 单位兼容的复合公式 ───────────────────────────────────

    @Unroll
    def "PRD 示例公式可求值：#expr"() {
        when:
        def point = evaluator.evaluate(formula(expr), inputs(), 1000L, 2000L)

        then:
        point.getOutcome() == CardPointOutcome.OK
        point.getValue() != null

        where:
        expr                    | _
        "failures / hits"       | _
        "tp99 - avgDuration"    | _
        "avgDuration * hits"    | _
        "hits + failures"       | _
    }

    def inputs() {
        return [(Stat.HITS): 100.0, (Stat.FAILURES): 10.0, (Stat.TP99): 200.0, (Stat.AVG): 50.0]
    }

    def card(String service, String kind, String type, String name, String labels, String formulaExpr) {
        return Card.withoutThresholds(1L, 1L, service, kind, type, name, labels, [], formulaExpr, "RECENT_24H", 0)
    }
}
