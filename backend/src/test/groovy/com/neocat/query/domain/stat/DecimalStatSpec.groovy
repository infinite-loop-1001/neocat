package com.neocat.query.domain.stat

import com.neocat.analysis.domain.bucket.AggregatedRow
import com.neocat.analysis.domain.bucket.AggregationLevel
import com.neocat.analysis.domain.bucket.DurationDistribution
import com.neocat.analysis.domain.bucket.MinuteBucket
import com.neocat.analysis.domain.bucket.SeriesKey
import com.neocat.analysis.domain.bucket.SeriesKind
import java.math.BigDecimal
import java.time.Instant
import spock.lang.Specification

class DecimalStatSpec extends Specification {
    def calculator = new StatCalculator()

    def '统计输入保持七位除法精度，公开结果在最后舍入六位'() {
        given:
        def row = row()
        row.addCount(3L, 2L, 1L, 0L, 1L)

        expect:
        calculator.computeIntermediate([row], Stat.AVG, 3L).toPlainString() == '0.3333333'
        calculator.compute([row], Stat.AVG, 3L).toPlainString() == '0.333333'
        calculator.compute([row], Stat.FAILURE_RATE, 3L).toPlainString() == '0.666667'
        calculator.compute([row], Stat.QPS, 3L).toPlainString() == '1.000000'
        calculator.compute([], Stat.HITS, 3L) == null
        calculator.compute([DecimalStatSpec.row()], Stat.AVG, 3L) == null
        calculator.compute([DecimalStatSpec.row()], Stat.HITS, 3L).toPlainString() == '0.000000'
    }

    def '超过 double 精确整数范围的计数与精确分位不丢最低位'() {
        given:
        long value = 9007199254740993L
        def row = row()
        row.addCount(value, 0L, 0L, value, value)
        def distribution = new DurationDistribution()
        distribution.record(value)

        expect:
        calculator.compute([row], Stat.HITS, 60L).toPlainString() == '9007199254740993.000000'
        distribution.percentile(0.99).toPlainString() == '9007199254740993.000000'
    }

    def '分箱段内除法使用七位，再在分位结果处舍入六位'() {
        given:
        def segments = new long[16]
        segments[3] = 3L
        def distribution = DurationDistribution.fromSegments(segments)

        expect:
        distribution.percentileIntermediate(0.1).toPlainString() == '10.6666664'
        distribution.percentile(0.1).toPlainString() == '10.666666'
    }

    def '数值观测累加是十进制运算且末值比较不受 scale 影响'() {
        given:
        def bucket = new MinuteBucket()
        def time = Instant.parse('2026-10-08T10:00:00Z')
        bucket.addValue(0.1, time)
        bucket.addValue(0.2, time)
        def row = row()
        row.addValue(bucket.valueSum(), bucket.valueCount())
        row.mergeLastValue(0.2, time)
        row.mergeLastValue(0.200000, time)

        expect:
        bucket.valueSum().compareTo(0.3) == 0
        bucket.valueAverage().toPlainString() == '0.150000'
        bucket.valueMin() == 0.1
        bucket.valueMax() == 0.2
        row.valueSum().compareTo(new BigDecimal('0.3')) == 0
        row.valueLast().compareTo(0.2) == 0
    }

    private static AggregatedRow row() {
        new AggregatedRow(SeriesKey.of('order', SeriesKind.TRANSACTION, 'URL', '/a', 'all'),
                Instant.parse('2026-10-08T10:00:00Z'), AggregationLevel.MINUTE, 60L)
    }
}
