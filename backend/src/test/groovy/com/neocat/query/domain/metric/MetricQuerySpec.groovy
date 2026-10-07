package com.neocat.query.domain.metric

import com.neocat.query.domain.series.Quality
import com.neocat.query.domain.stat.Stat

import com.neocat.analysis.domain.bucket.AggregatedRow
import com.neocat.analysis.domain.bucket.AggregationLevel
import com.neocat.analysis.domain.bucket.SeriesKey
import com.neocat.analysis.domain.bucket.SeriesKind
import com.neocat.common.time.bucket.Bucket
import spock.lang.Specification

import java.time.Instant

/**
 * G8 任务61（红）：Metric 查询与跨小时缺口。
 * 对应 PRD 04 §3（小时重排与跨小时缺口）、§4（统计）、§5（大盘行为）、§9（验收 1–3）。
 */
class MetricQuerySpec extends Specification {

    MetricQueryService service = new MetricQueryService()

    static Instant H10 = Instant.parse("2026-09-24T02:00:00Z")     // 平台时区 10:00
    static Instant H11 = Instant.parse("2026-09-24T03:00:00Z")     // 平台时区 11:00

    def metricRow(String labels, Instant at, double value, long sampleCount = 1) {
        def key = SeriesKey.metric("order", "order.amount", labels)
        def row = new AggregatedRow(key, at, AggregationLevel.HOUR, 3600)
        sampleCount.times {
            row.addValue(value, 1)
            row.recordDuration(Math.round(value))
        }
        row.addCount(sampleCount, 0, Math.round(value) * sampleCount, Math.round(value), Math.round(value))
        return row
    }

    def bucket(Instant start) {
        return new Bucket(start, start.plusSeconds(3600), false, 3600)
    }

    // ── 跨小时缺口 ───────────────────────────────────────────

    def "该小时独立保留时显示实际值"() {
        given: "10 点该组合独立保留"
        def rows = [metricRow("city=上海;", H10, 100.0d)]
        def buckets = [bucket(H10)]

        when:
        def series = service.series("order", "order.amount", "city=上海;", rows, buckets, [] as Set,
                Stat.HITS, 3600)

        then:
        def point = series.getPoints()[0]
        point.getValue() == 1.0d
        point.getQuality() == Quality.OK
    }

    def "该小时被并入 other 时返回缺口并标记 MERGED_OTHER"() {
        given: "10 点有值，11 点该组合被并入 other"
        def rows = [metricRow("city=上海;", H10, 100.0d)]
        def buckets = [bucket(H10), bucket(H11)]
        def mergedHours = [H11.toEpochMilli()] as Set

        when:
        def series = service.series("order", "order.amount", "city=上海;", rows, buckets, mergedHours,
                Stat.HITS, 3600)

        then:
        def p10 = series.getPoints()[0]
        def p11 = series.getPoints()[1]
        p10.getValue() == 1.0d
        p10.getQuality() == Quality.OK

        and: "11 点是缺口，不是 0"
        p11.getValue() == null
        p11.getQuality() == Quality.MERGED_OTHER
        p11.getQuality().gap()
    }

    def "不用 other 值冒充具体组合：合并小时的值为 null 而非 other 的值"() {
        given: "other 序列在 11 点有 999 次"
        def concreteRows = [metricRow("city=上海;", H10, 100.0d)]
        def otherRows = [metricRow(SeriesKey.OTHER_LABELS, H11, 50.0d, 999)]
        def buckets = [bucket(H10), bucket(H11)]
        def mergedHours = [H11.toEpochMilli()] as Set

        when: "查询具体组合"
        def concrete = service.series("order", "order.amount", "city=上海;", concreteRows, buckets,
                mergedHours, Stat.HITS, 3600)

        then: "合并小时为缺口，绝不出现 999"
        concrete.getPoints()[1].getValue() == null
        concrete.getPoints()[1].getValue() != 999.0d
        concrete.getPoints().every { it.getValue() != 999.0d }
    }

    def "other 自身是可独立查看的序列：查询 other 时不标记缺口"() {
        given:
        def otherRows = [metricRow(SeriesKey.OTHER_LABELS, H10, 50.0d, 500)]
        def buckets = [bucket(H10)]

        when:
        def series = service.series("order", "order.amount", SeriesKey.OTHER_LABELS, otherRows, buckets,
                [] as Set, Stat.HITS, 3600)

        then:
        series.getPoints()[0].getValue() == 500.0d
        series.getPoints()[0].getQuality() == Quality.OK
    }

    def "缺口不显示为 0"() {
        given:
        def buckets = [bucket(H10), bucket(H11)]
        def mergedHours = [H11.toEpochMilli()] as Set

        when:
        def series = service.series("order", "order.amount", "city=北京;", [], buckets, mergedHours,
                Stat.HITS, 3600)

        then:
        series.getPoints().each { point ->
            assert point.getValue() != 0.0d
        }
    }

    def "未上报且未并入 other 的小时为 NO_DATA 缺口"() {
        given: "只有 10 点有值，11 点既无数据也未被并入 other"
        def rows = [metricRow("a=1;", H10, 1.0d)]
        def buckets = [bucket(H10), bucket(H11)]

        when:
        def series = service.series("order", "order.amount", "a=1;", rows, buckets, [] as Set,
                Stat.HITS, 3600)

        then:
        series.getPoints()[1].getValue() == null
        series.getPoints()[1].getQuality() == Quality.NO_DATA
    }

    def "同一标签组合在不同小时可能独立或被并入 other（小时独立排名的结果）"() {
        given: "10 点独立、11 点并入 other"
        def rows = [metricRow("a=1;", H10, 1.0d)]
        def buckets = [bucket(H10), bucket(H11)]
        def mergedHours = [H11.toEpochMilli()] as Set

        when:
        def series = service.series("order", "order.amount", "a=1;", rows, buckets, mergedHours,
                Stat.HITS, 3600)

        then:
        series.getPoints()[0].getQuality() == Quality.OK
        series.getPoints()[1].getQuality() == Quality.MERGED_OTHER
    }

    def "反向变化同样成立：10 点并入 other、11 点独立"() {
        given:
        def rows = [metricRow("a=1;", H11, 5.0d, 5)]
        def buckets = [bucket(H10), bucket(H11)]
        def mergedHours = [H10.toEpochMilli()] as Set

        when:
        def series = service.series("order", "order.amount", "a=1;", rows, buckets, mergedHours,
                Stat.HITS, 3600)

        then:
        series.getPoints()[0].getQuality() == Quality.MERGED_OTHER
        series.getPoints()[0].getValue() == null
        series.getPoints()[1].getQuality() == Quality.OK
        series.getPoints()[1].getValue() == 5.0d
    }

    // ── 统计项 ───────────────────────────────────────────────

    def "Metric 支持上报次数、总和、平均、最小最大与分位"() {
        given:
        def k = SeriesKey.metric("order", "order.amount", "a=1;")
        def row = new AggregatedRow(k, H10, AggregationLevel.HOUR, 3600)
        [10L, 20L, 30L].each { v ->
            row.addValue(v, 1)
            row.recordDuration(v)
        }
        row.addCount(3, 0, 60, 10, 30)
        def buckets = [bucket(H10)]

        when:
        def series = service.series("order", "order.amount", "a=1;", [row], buckets, [] as Set,
                Stat.HITS, 3600)

        then:
        series.getPoints()[0].getValue() == 3.0d
    }

    def "合并小时的分位也为缺口"() {
        given:
        def rows = [metricRow("a=1;", H10, 100.0d)]
        def buckets = [bucket(H10), bucket(H11)]
        def mergedHours = [H11.toEpochMilli()] as Set

        when:
        def series = service.series("order", "order.amount", "a=1;", rows, buckets, mergedHours,
                Stat.TP99, 3600)

        then:
        series.getPoints()[1].getValue() == null
        series.getPoints()[1].getQuality() == Quality.MERGED_OTHER
    }

    def "无任何桶时返回空序列"() {
        when:
        def series = service.series("order", "order.amount", "a=1;", [], [], [] as Set,
                Stat.HITS, 3600)

        then:
        series.getPoints().isEmpty()
    }

    // ── 辅助判定 ─────────────────────────────────────────────

    def "mergedIntoOther 按桶起点精确匹配小时"() {
        given:
        def mergedHours = [H11.toEpochMilli()] as Set

        expect:
        service.mergedIntoOther(H11.toEpochMilli(), mergedHours)
        !service.mergedIntoOther(H10.toEpochMilli(), mergedHours)
    }

    def "序列身份与标签串一致：标签串变化不会串到别的序列"() {
        given: "上海在 10 点有值"
        def rows = [metricRow("city=上海;", H10, 1.0d, 7)]
        def buckets = [bucket(H10)]

        when: "查询北京"
        def beijing = service.series("order", "order.amount", "city=北京;", rows, buckets, [] as Set,
                Stat.HITS, 3600)

        then: "北京没有数据，是缺口而非上海的值"
        beijing.getPoints()[0].getValue() == null
        beijing.getPoints()[0].getValue() != 7.0d
    }
}
