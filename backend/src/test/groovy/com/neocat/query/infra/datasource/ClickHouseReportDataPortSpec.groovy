package com.neocat.query.infra.datasource

import com.neocat.analysis.domain.bucket.AggregationLevel
import com.neocat.analysis.domain.bucket.SeriesKey
import com.neocat.analysis.domain.bucket.SeriesKind
import com.neocat.common.time.bucket.Granularity
import com.neocat.query.infra.datasource.row.BucketRow
import spock.lang.Specification

import java.time.Instant

/**
 * ClickHouse 报表读取适配器的规格。
 *
 * <p>验证三条关键规则：
 * 1. 空桶不产生行（缺口不被伪造成「确认无调用」）；
 * 2. 分布从持久化数组还原为分箱模式，仍参与「合并后重算分位」；
 * 3. 范围长度决定查分钟桶还是小时桶。
 */
class ClickHouseReportDataPortSpec extends Specification {

    static final Instant FROM = Instant.parse("2026-09-24T00:00:00Z")

    ClickHouseReportQuery query
    List<BucketRow> minuteRows
    List<BucketRow> hourRows
    ClickHouseReportDataPort port

    def setup() {
        minuteRows = []
        hourRows = []
        query = Stub(ClickHouseReportQuery) {
            minuteRows(_, _, _, _, _, _, _) >> { minuteRows }
            hourRows(_, _, _, _, _, _, _) >> { hourRows }
        }
        port = new ClickHouseReportDataPort(query)
    }

    /** 构造 16 段分布：把给定样本数放在指定段。 */
    static long[] distribution(int index, long value) {
        def result = emptyDistribution()
        result[index] = value
        return result
    }

    /** 全零分布（无样本的桶）。 */
    static long[] emptyDistribution() {
        return new long[16]
    }

    static BucketRow row(long count, long fail, long durationSum,
                                              long min, long max, long[] distribution,
                                              long covered, Instant start = FROM) {
        return new BucketRow(
                "order", "TRANSACTION", "URL", "POST /orders", SeriesKey.ALL, "", "",
                start, AggregationLevel.MINUTE,
                count, fail, durationSum, min, max, 0d, 0L, distribution, covered)
    }

    // ── 行转换 ───────────────────────────────────────────────

    def "有数据的桶被转换为聚合行"() {
        given:
        minuteRows = [row(100, 5, 5000, 10, 200, distribution(3, 10L), 60)]

        when:
        def rows = port.rows("TRANSACTION", "order", "URL", "POST /orders", FROM, FROM.plusSeconds(600), Granularity.MINUTE_1, [])

        then:
        rows.size() == 1
        rows[0].count() == 100
        rows[0].failCount() == 5
        rows[0].durationSum() == 5000
        rows[0].durationMin() == 10
        rows[0].durationMax() == 200
        rows[0].coveredSeconds() == 60
    }

    def "空桶不产生行：缺数据不被伪装成确认无调用"() {
        given: "两个空桶与一个有数据的桶"
        minuteRows = [
                row(0, 0, 0, 0, 0, emptyDistribution(), 60, FROM),
                row(0, 0, 0, 0, 0, emptyDistribution(), 60, FROM.plusSeconds(60)),
                row(50, 0, 500, 10, 10, distribution(3, 50L), 60, FROM.plusSeconds(120))
        ]

        when:
        def rows = port.rows("TRANSACTION", "order", "URL", "POST /orders", FROM, FROM.plusSeconds(600), Granularity.MINUTE_1, [])

        then: "只有第 3 个桶成为行"
        rows.size() == 1
        rows[0].bucketStart() == FROM.plusSeconds(120)
        rows.every { it.count() > 0 }
    }

    def "只有数值而无次数的桶（Metric 场景）也会产生行"() {
        given:
        def metricRow = new BucketRow(
                "order", "METRIC", "order.amount", "", SeriesKey.ALL, "", "city=上海;",
                FROM, AggregationLevel.MINUTE, 0L, 0L, 0L, 0L, 0L, 128.5d, 3L, emptyDistribution(), 60L)
        minuteRows = [metricRow]

        when:
        def rows = port.rows("METRIC", "order", "order.amount", "city=上海;", FROM, FROM.plusSeconds(600), Granularity.MINUTE_1, [])

        then: "仅凭 valueCount 判断为有数据"
        rows.size() == 1
        rows[0].valueSum() == 128.5d
        rows[0].valueCount() == 3
    }

    // ── 分布还原 ─────────────────────────────────────────────

    def "分布从持久化数组还原，分位仍基于合并后的直方图"() {
        given: "10 个样本落在 10ms 段、90 个落在 1000ms 段"
        def distribution = emptyDistribution()
        distribution[3] = 10L     // [8,16) ms
        distribution[9] = 90L     // [512,1024) ms
        minuteRows = [row(100, 0, 91000, 10, 1000, distribution, 60)]

        when:
        def rows = port.rows("TRANSACTION", "order", "URL", "POST /orders", FROM, FROM.plusSeconds(600), Granularity.MINUTE_1, [])
        def merged = rows[0].distribution()

        then: "样本总数与段数一致"
        merged.count() == 100
        merged.segments().sum() == 100

        and: "分布处于分箱模式（ClickHouse 未保留原始值）"
        !merged.exact()

        and: "p50 落在 1000ms 段，而不是「10 与 1000 的平均」"
        merged.percentile(0.50d) >= 512.0d
        merged.percentile(0.50d) > 505.0d
    }

    def "分布数组长度异常时按段数截断，不导致读取失败"() {
        given: "持久化数据只有 10 段（历史格式差异或未来扩展）"
        minuteRows = [row(5, 0, 50, 10, 10, Arrays.copyOf(distribution(3, 5L), 10), 60)]

        when:
        def rows = port.rows("TRANSACTION", "order", "URL", "POST /orders", FROM, FROM.plusSeconds(600), Granularity.MINUTE_1, [])

        then:
        noExceptionThrown()
        rows[0].distribution().count() == 5
        rows[0].distribution().segments().length == 16
    }

    // ── 源表选择：由**请求粒度**决定，而不是范围长度 ──────────
    //
    // 依据：技术方案 03 §4.1「bucket 省略时按 range 的默认粒度」。
    // 范围长不代表点要粗 —— 「3 天看每分钟」是合法请求，按范围长度选表会让它拿不到数据。

    def "分钟粒度查分钟桶（与范围长度无关）"() {
        given:
        query = Mock(ClickHouseReportQuery)
        port = new ClickHouseReportDataPort(query)
        when:
        port.rows("TRANSACTION", "order", "URL", "POST /orders", FROM, FROM.plusSeconds(3600),
                Granularity.MINUTE_1, [])

        then:
        1 * query.minuteRows('order', 'TRANSACTION', 'URL', 'POST /orders', SeriesKey.ALL,
                FROM, FROM.plusSeconds(3600)) >> []
        0 * query.hourRows(_, _, _, _, _, _, _)
        0 * query.dayRows(_, _, _, _, _, _, _)
    }

    def "长范围 + 分钟粒度仍查分钟桶（不被范围长度改写成小时桶）"() {
        given:
        query = Mock(ClickHouseReportQuery)
        port = new ClickHouseReportDataPort(query)
        when:
        port.rows("TRANSACTION", "order", "URL", "POST /orders", FROM, FROM.plusSeconds(3 * 86400),
                Granularity.MINUTE_1, [])

        then:
        1 * query.minuteRows(_, _, _, _, _, _, _) >> []
        0 * query.hourRows(_, _, _, _, _, _, _)
    }

    def "小时粒度查小时桶"() {
        given:
        query = Mock(ClickHouseReportQuery)
        port = new ClickHouseReportDataPort(query)
        when:
        port.rows("TRANSACTION", "order", "URL", "POST /orders", FROM, FROM.plusSeconds(6 * 3600),
                Granularity.HOUR_1, [])

        then:
        1 * query.hourRows('order', 'TRANSACTION', 'URL', 'POST /orders', SeriesKey.ALL,
                FROM, FROM.plusSeconds(6 * 3600)) >> []
        0 * query.minuteRows(_, _, _, _, _, _, _)
    }

    def "日粒度查日桶"() {
        given:
        query = Mock(ClickHouseReportQuery)
        port = new ClickHouseReportDataPort(query)
        when:
        port.rows("TRANSACTION", "order", "URL", "POST /orders", FROM, FROM.plusSeconds(3 * 86400),
                Granularity.DAY_1, [])

        then:
        1 * query.dayRows('order', 'TRANSACTION', 'URL', 'POST /orders', SeriesKey.ALL,
                FROM, FROM.plusSeconds(3 * 86400)) >> []
        0 * query.minuteRows(_, _, _, _, _, _, _)
        0 * query.hourRows(_, _, _, _, _, _, _)
    }

    def "短范围 + 小时粒度查小时桶（不被范围长度改写成分钟桶）"() {
        given:
        query = Mock(ClickHouseReportQuery)
        port = new ClickHouseReportDataPort(query)
        when:
        port.rows("TRANSACTION", "order", "URL", "POST /orders", FROM, FROM.plusSeconds(3600),
                Granularity.HOUR_1, [])

        then:
        1 * query.hourRows(_, _, _, _, _, _, _) >> []
        0 * query.minuteRows(_, _, _, _, _, _, _)
    }

    // ── 实例维度 ─────────────────────────────────────────────

    def "不传实例时查询全机器聚合行"() {
        given:
        query = Mock(ClickHouseReportQuery)
        port = new ClickHouseReportDataPort(query)
        when:
        port.rows("TRANSACTION", "order", "URL", "POST /orders", FROM, FROM.plusSeconds(600), Granularity.MINUTE_1, [])

        then:
        1 * query.minuteRows('order', 'TRANSACTION', 'URL', 'POST /orders', SeriesKey.ALL,
                FROM, FROM.plusSeconds(600)) >> []
    }

    def "传实例时按实例逐行查询"() {
        given:
        query = Mock(ClickHouseReportQuery)
        port = new ClickHouseReportDataPort(query)
        when:
        port.rows("TRANSACTION", "order", "URL", "POST /orders", FROM, FROM.plusSeconds(600), Granularity.MINUTE_1, ["10.0.0.8", "10.0.0.9"])

        then: "两个实例各查一次"
        1 * query.minuteRows('order', 'TRANSACTION', 'URL', 'POST /orders', '10.0.0.8',
                FROM, FROM.plusSeconds(600)) >> []
        1 * query.minuteRows('order', 'TRANSACTION', 'URL', 'POST /orders', '10.0.0.9',
                FROM, FROM.plusSeconds(600)) >> []
    }

    // ── 目录过滤 ─────────────────────────────────────────────

    def "实例 / 分类 / 名称列表直接委托查询层"() {
        given:
        query = Stub(ClickHouseReportQuery) {
            distinctInstances('order', 'TRANSACTION', FROM, FROM.plusSeconds(600)) >> ['10.0.0.8']
            distinctTypes('order', 'TRANSACTION', FROM, FROM.plusSeconds(600)) >> ['URL']
            distinctNames('order', 'TRANSACTION', 'URL', FROM, FROM.plusSeconds(600)) >> ['POST /orders']
        }
        port = new ClickHouseReportDataPort(query)

        expect:
        port.instancesWithData("TRANSACTION", "order", FROM, FROM.plusSeconds(600)) == ["10.0.0.8"]
        port.typesOf("TRANSACTION", "order", FROM, FROM.plusSeconds(600)) == ["URL"]
        port.namesOf("TRANSACTION", "order", "URL", FROM, FROM.plusSeconds(600)) == ["POST /orders"]
    }

    // ── 质量事件 ─────────────────────────────────────────────

    def "丢弃质量事件被透传，用于把该桶标为缺口"() {
        given:
        query = Mock(ClickHouseReportQuery)
        port = new ClickHouseReportDataPort(query)

        when:
        def dropped = port.droppedAt("TRANSACTION", "order", "URL", "POST /orders", FROM)

        then:
        1 * query.hasDropEvent('order', 'TRANSACTION', 'URL', 'POST /orders', FROM) >> true
        dropped
    }

    def "Metric 合并信息被透传，用于把该小时标为缺口"() {
        given:
        query = Mock(ClickHouseReportQuery)
        port = new ClickHouseReportDataPort(query)

        when:
        def merged = port.mergedIntoOther("order", "order.amount", "city=上海;", FROM)

        then:
        1 * query.mergedIntoOther('order', 'order.amount', 'city=上海;', FROM) >> true
        merged
    }

    // ── 序列身份 ─────────────────────────────────────────────

    def "转换后的行保留完整序列身份（含 Problem 分类与 Metric 标签）"() {
        given:
        def problemRow = new BucketRow(
                "order", "PROBLEM", "SLOW_SQL", "select_order", SeriesKey.ALL, "SLOW_SQL", "",
                FROM, AggregationLevel.MINUTE, 7L, 0L, 2100L, 300L, 400L, 0d, 0L, emptyDistribution(), 60L)
        minuteRows = [problemRow]

        when:
        def rows = port.rows("PROBLEM", "order", "SLOW_SQL", "select_order", FROM, FROM.plusSeconds(600), Granularity.MINUTE_1, [])

        then:
        rows[0].key().getProblemCategory() == "SLOW_SQL"
        rows[0].key().getKind() == SeriesKind.PROBLEM
        rows[0].key().getName() == "select_order"
    }
}
