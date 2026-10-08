package com.neocat.dashboard.domain.card

import com.neocat.dashboard.domain.formula.FormulaParser
import com.neocat.dashboard.domain.formula.Formula
import com.neocat.dashboard.domain.formula.FormulaOperator
import com.neocat.query.domain.stat.Stat
import java.math.BigDecimal
import spock.lang.Specification

class DecimalCardSpec extends Specification {
    def parser = new FormulaParser()
    def evaluator = new CardEvaluator()

    def '常量从原文解析且加减乘不对中间值舍入'() {
        expect:
        evaluate('hits + failures', [(Stat.HITS): 0.1, (Stat.FAILURES): 0.2]).getValue().toPlainString() == '0.300000'
        evaluate('9007199254740993 - 9007199254740992').getValue().toPlainString() == '1.000000'
        evaluate('(hits + failures) * 10', [(Stat.HITS): 0.0000004, (Stat.FAILURES): 0.0000004])
                .getValue().toPlainString() == '0.000008'
        evaluate('hits * 100', [(Stat.HITS): 0.123456789]).getValue().toPlainString() == '12.345679'
    }

    def '除法在中间保留七位，乘法后才做最终六位舍入'() {
        expect:
        evaluate('(1 / 3) * 3').getValue().toPlainString() == '1.000000'
        evaluate('(1 / 3) * 10').getValue().toPlainString() == '3.333333'
        evaluator.evaluate(parser.parse('avgDuration * 10').getFormula(),
                [(Stat.AVG): 0.3333333], 0L, 60000L).getValue().toPlainString() == '3.333333'
    }

    def '子表达式除零向上传播且已知极小除数不能提前变成零'() {
        expect:
        evaluate('(1 / 0) + 1').isUndefined()
        evaluate('1 / (1 / 0)').isUndefined()
        evaluator.evaluate(new Formula.Binary(FormulaOperator.DIVIDE,
                new Formula.Constant(1.0), new Formula.Constant(0.00000001)), [:], 0L, 60000L)
                .getValue().toPlainString() == '100000000.000000'
        evaluator.evaluate(parser.parse('failures / hits').getFormula(),
                [(Stat.HITS): 0.000000, (Stat.FAILURES): null], 0L, 60000L).isGap()
    }

    private CardPoint evaluate(String formula, Map<Stat, BigDecimal> inputs = [:]) {
        def parsed = parser.parse(formula)
        assert parsed.valid()
        evaluator.evaluate(parsed.getFormula(), inputs, 0L, 60000L)
    }
}
