package com.neocat.common

import java.math.BigDecimal
import spock.lang.Specification

class DecimalMathSpec extends Specification {
    def '除法七位四舍五入，最终结果六位四舍五入'() {
        expect:
        DecimalMath.divide(1L, 3L).toPlainString() == '0.3333333'
        DecimalMath.divide(2L, 3L).toPlainString() == '0.6666667'
        DecimalMath.result(DecimalMath.divide(2L, 3L)).toPlainString() == '0.666667'
        DecimalMath.result(new BigDecimal('1.2345675')).toPlainString() == '1.234568'
        DecimalMath.result(new BigDecimal('-1.2345675')).toPlainString() == '-1.234568'
        DecimalMath.result(null) == null
    }
}
