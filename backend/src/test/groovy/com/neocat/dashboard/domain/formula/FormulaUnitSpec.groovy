package com.neocat.dashboard.domain.formula

import com.neocat.query.domain.stat.Stat
import spock.lang.Specification
import spock.lang.Unroll

/**
 * G9 任务67（红）：公式解析与单位校验。
 * 对应 PRD 05 §4（统计项与公式：聚合、四则运算、常数、单位校验、除零）、
 * §11（验收 3、4：不允许跨服务/跨 Name 公式、单位非法不能保存）。
 */
class FormulaUnitSpec extends Specification {

    FormulaParser parser = new FormulaParser()

    // ── 基本解析 ─────────────────────────────────────────────

    def "解析裸统计项"() {
        when:
        def r = parser.parse("hits")

        then:
        r.valid()
        r.getFormula() instanceof Formula.Ref
        (r.getFormula() as Formula.Ref).getStat() == Stat.HITS
    }

    def "解析聚合函数 sum(hits)"() {
        when:
        def r = parser.parse("sum(hits)")

        then:
        r.valid()
        r.getFormula() instanceof Formula.Aggregate
        (r.getFormula() as Formula.Aggregate).getAgg() == FormulaAggregate.SUM
        (r.getFormula() as Formula.Aggregate).getStat() == Stat.HITS
    }

    @Unroll
    def "解析聚合函数 #agg"() {
        when:
        def r = parser.parse("$agg(hits)")

        then:
        r.valid()
        (r.getFormula() as Formula.Aggregate).getAgg() == expected

        where:
        agg     | expected
        "sum"   | FormulaAggregate.SUM
        "avg"   | FormulaAggregate.AVG
        "min"   | FormulaAggregate.MIN
        "max"   | FormulaAggregate.MAX
    }

    def "解析并忽略大小写与空白"() {
        expect:
        parser.parse("  SUM( HITS )  ").valid()
        parser.parse("FailureRate").valid()
    }

    // ── 四则运算 ─────────────────────────────────────────────

    def "解析四则运算"() {
        when:
        def r = parser.parse("failures / hits")

        then:
        r.valid()
        r.getFormula() instanceof Formula.Binary
        (r.getFormula() as Formula.Binary).getOp() == FormulaOperator.DIVIDE
    }

    @Unroll
    def "解析运算符 #op"() {
        when:
        def r = parser.parse("hits $op failures")

        then:
        r.valid()
        (r.getFormula() as Formula.Binary).getOp() == expected

        where:
        op | expected
        "+" | FormulaOperator.ADD
        "-" | FormulaOperator.SUBTRACT
        "*" | FormulaOperator.MULTIPLY
        "/" | FormulaOperator.DIVIDE
    }

    def "乘除优先级高于加减"() {
        when: "tp99 + avgDuration / 2 —— 单位兼容，可被接受"
        def r = parser.parse("tp99 + avgDuration / 2")

        then: "根节点是加法，右子树是除法"
        r.valid()
        def root = r.getFormula() as Formula.Binary
        root.getOp() == FormulaOperator.ADD
        (root.getRight() as Formula.Binary).getOp() == FormulaOperator.DIVIDE
    }

    def "单位不兼容的复合公式在解析期被拒（hits + failures / hits）"() {
        when:
        def r = parser.parse("hits + failures / hits")

        then: "右侧为比例、左侧为次数，单位不兼容"
        !r.valid()
        r.getError() == "UNIT_MISMATCH"
    }

    def "括号改变优先级"() {
        when: "(hits + failures) / hits"
        def r = parser.parse("(hits + failures) / hits")

        then:
        def root = r.getFormula() as Formula.Binary
        root.getOp() == FormulaOperator.DIVIDE
        (root.getLeft() as Formula.Binary).getOp() == FormulaOperator.ADD
    }

    def "解析常数参与运算"() {
        when:
        def r = parser.parse("hits * 100")

        then:
        r.valid()
        def root = r.getFormula() as Formula.Binary
        root.getOp() == FormulaOperator.MULTIPLY
        (root.getRight() as Formula.Constant).getValue() == 100.0d
    }

    def "解析 PRD 中的示例公式"() {
        expect: "PRD 05 §4 列出的例子"
        parser.parse("failures / hits").valid()
        parser.parse("tp99").valid()
        parser.parse("avgDuration").valid()
        parser.parse("tp99 - avgDuration").valid()
    }

    // ── 不支持的形式 ─────────────────────────────────────────

    @Unroll
    def "拒绝不支持的形式：#expr"() {
        when:
        def r = parser.parse(expr)

        then:
        !r.valid()
        r.getError() == "FORMULA_INVALID"

        where:
        expr << [
                "",                                     // 空
                "   ",                                  // 空白
                "hits +",                               // 缺右操作数
                "(hits",                                // 未闭合括号
                "hits hits",                            // 多余 token
                "unknownStat",                          // 未知统计项
                "sum()",                                // 聚合函数缺参数
                "sum(hits, failures)",                  // 聚合函数参数过多（跨 Name 会被上层拒绝）
                "if(hits > 10, 1, 0)",                  // 条件表达式
                "hits > 10",                            // 比较
                "order.hits + pay.hits",                // 跨服务公式
                "hits; failures",                       // 语句分隔
                "hits ** 2",                            // 幂运算
                "Math.max(hits, failures)"              // 自由脚本
        ]
    }

    // ── 单位推导 ─────────────────────────────────────────────

    @Unroll
    def "统计项单位：#stat → #unit"() {
        expect:
        Unit.of(stat) == unit

        where:
        stat              | unit
        Stat.HITS         | Unit.COUNT
        Stat.FAILURES     | Unit.COUNT
        Stat.QPS          | Unit.RATE
        Stat.FAILURE_RATE | Unit.RATE
        Stat.AVG          | Unit.DURATION
        Stat.TP99         | Unit.DURATION
    }

    def "加法要求单位兼容"() {
        expect:
        parser.parse("hits + failures").valid()
        parser.parse("tp99 + avgDuration").valid()
    }

    def "单位不兼容的加法被拒绝"() {
        when: "耗时 + 次数"
        def r = parser.parse("tp99 + hits")

        then:
        !r.valid()
        r.getError() == "UNIT_MISMATCH"
    }

    def "单位不兼容的减法被拒绝"() {
        when:
        def r = parser.parse("avgDuration - failures")

        then:
        !r.valid()
        r.getError() == "UNIT_MISMATCH"
    }

    def "乘法推导单位"() {
        expect: "平均耗时 × 次数 = 耗时"
        parser.parse("avgDuration * hits").valid()
        and:
        (parser.parse("avgDuration * hits").getFormula() as Formula.Binary).unit() == Unit.DURATION
    }

    def "除法推导单位"() {
        when: "失败次数 / 总次数 = 比例"
        def r = parser.parse("failures / hits")

        then:
        r.valid()
        (r.getFormula() as Formula.Binary).unit() == Unit.RATE
    }

    def "常数参与运算不改变单位"() {
        expect:
        (parser.parse("hits * 100").getFormula() as Formula.Binary).unit() == Unit.COUNT
        (parser.parse("100 * hits").getFormula() as Formula.Binary).unit() == Unit.COUNT
        (parser.parse("hits / 60").getFormula() as Formula.Binary).unit() == Unit.COUNT
    }

    def "PRD 示例公式的单位推导"() {
        expect:
        (parser.parse("failures / hits").getFormula() as Formula.Binary).unit() == Unit.RATE
        (parser.parse("tp99 - avgDuration").getFormula() as Formula.Binary).unit() == Unit.DURATION
        (parser.parse("tp99").getFormula() as Formula.Ref).unit() == Unit.DURATION
    }

    def "validateUnits 对合法公式返回 null"() {
        expect:
        parser.validateUnits(parser.parse("failures / hits").getFormula()) == null
        parser.validateUnits(parser.parse("tp99 + avgDuration").getFormula()) == null
    }

    // ── 引用的统计项 ─────────────────────────────────────────

    def "公式暴露引用的统计项（供建立卡片与告警目标的依赖关系）"() {
        when:
        def formula = parser.parse("failures / hits").getFormula()

        then:
        formula.referencedStats() as Set == [Stat.FAILURES, Stat.HITS] as Set
    }

    def "聚合函数保留其统计项引用"() {
        when: "同一指标对象的多个统计项聚合（单位兼容）"
        def formula = parser.parse("sum(tp99) + avg(tp95)").getFormula()

        then:
        formula.referencedStats() as Set == [Stat.TP99, Stat.TP95] as Set
    }

    def "聚合函数包裹次数类统计项"() {
        when:
        def formula = parser.parse("sum(hits) + max(failures)").getFormula()

        then:
        formula.referencedStats() as Set == [Stat.HITS, Stat.FAILURES] as Set
    }

    def "常数不引入统计项引用"() {
        expect:
        parser.parse("100").getFormula().referencedStats().isEmpty()
    }

    def "重复引用去重后仍可枚举"() {
        when:
        def formula = parser.parse("hits + hits").getFormula()

        then:
        formula.referencedStats().count { it == Stat.HITS } == 2
    }

    // ── 单位运算规则 ─────────────────────────────────────────

    def "同单位相除得到比例"() {
        expect:
        Unit.DURATION.divide(Unit.DURATION) == Unit.RATE
        Unit.COUNT.divide(Unit.COUNT) == Unit.RATE
    }

    def "耗时总和除以次数得到平均耗时"() {
        expect:
        Unit.DURATION.divide(Unit.COUNT) == Unit.RATIO
    }

    def "次数乘以平均耗时得到耗时"() {
        expect:
        Unit.COUNT.multiply(Unit.RATIO) == Unit.DURATION
        Unit.RATIO.multiply(Unit.COUNT) == Unit.DURATION
    }

    def "常数与任何单位运算保持该单位"() {
        expect:
        Unit.NUMBER.multiply(Unit.COUNT) == Unit.COUNT
        Unit.DURATION.multiply(Unit.NUMBER) == Unit.DURATION
        Unit.DURATION.divide(Unit.NUMBER) == Unit.DURATION
    }
}
