package com.neocat.alert.domain.rule

import com.neocat.query.domain.stat.Stat
import java.math.BigDecimal
import spock.lang.Specification

class DecimalConditionSpec extends Specification {
    def '六种比较只比较数值，不比较 scale，缺数不满足任意条件'() {
        expect:
        new Condition(Stat.HITS, comparator, 1.000000).matches(value) == expected
        !new Condition(Stat.HITS, comparator, 1.000000).matches(null)

        where:
        comparator     | value | expected
        Comparator.EQ  | 1.0   | true
        Comparator.NEQ | 1.0   | false
        Comparator.GT  | 1.1   | true
        Comparator.GTE | 1.0   | true
        Comparator.LT  | 0.9   | true
        Comparator.LTE | 1.0   | true
        Comparator.EQ  | 1.1   | false
        Comparator.NEQ | 1.1   | true
    }

    def '正负零数值相等，大阈值仍能区别最小六位增量'() {
        expect:
        new Condition(Stat.HITS, Comparator.EQ, 0.000000).matches(-0.0)
        new Condition(Stat.HITS, Comparator.GT, 99999999999999.999998)
                .matches(99999999999999.999999)
    }

    def '领域边界拒绝空阈值'() {
        when:
        new Condition(Stat.HITS, Comparator.EQ, null)
        then:
        thrown(NullPointerException)
    }

    def '超出 DECIMAL(20,6) 的配置不静默截断'() {
        when:
        new Condition(Stat.HITS, Comparator.EQ, new BigDecimal(value))
        then:
        thrown(IllegalArgumentException)
        where:
        value << ['0.0000001', '100000000000000', '-100000000000000']
    }
}
