package com.neocat.query.domain.report

import com.neocat.analysis.domain.bucket.AggregatedRow
import com.neocat.analysis.domain.bucket.AggregationLevel
import com.neocat.analysis.domain.bucket.SeriesKey
import com.neocat.analysis.domain.bucket.SeriesKind
import spock.lang.Specification
import spock.lang.Unroll

import java.time.Instant

/**
 * G8 任务57（红）：Type / Name 报表表。
 * 对应 PRD 03 §7.1（Type 层字段与「只进 Name 列表」）、§7.2（Name 层限定 Type）、
 * §8（Event 无耗时无分位）、§9（异常无分位、慢类有分位）。
 */
class ReportTableSpec extends Specification {

    static final Instant HOUR = Instant.parse("2026-09-24T04:00:00Z")

    ReportTableService service = new ReportTableService()

    def row(String service_, SeriesKind kind, String type, String name,
            long count, long fail, long durationSum, long min, long max, List<Long> samples) {
        def key = new SeriesKey(service_, kind, type, name, SeriesKey.ALL, "", "")
        def r = new AggregatedRow(key, HOUR, AggregationLevel.HOUR, 3600)
        r.addCount(count, fail, durationSum, min, max)
        samples.each { s -> r.recordDuration(s) }
        return r
    }

    def urlRows() {
        [
                row("order", SeriesKind.TRANSACTION, "URL", "/a", 100, 5, 5000, 10, 200, (1..100).collect { 50L }),
                row("order", SeriesKind.TRANSACTION, "URL", "/b", 50, 1, 2500, 20, 300, (1..50).collect { 50L }),
                row("order", SeriesKind.TRANSACTION, "SQL", "s1", 200, 0, 2000, 5, 30, (1..200).collect { 10L })
        ]
    }

    // ── Type 层 ──────────────────────────────────────────────

    def "Type 汇总按 Type 分组"() {
        when:
        def table = service.typeTable("TRANSACTION", urlRows(), 3600)

        then:
        table*.getType() as Set == ["URL", "SQL"] as Set
    }

    def "Type 汇总包含设计要求的全部字段"() {
        when:
        def url = service.typeTable("TRANSACTION", urlRows(), 3600).find { it.getType() == "URL" }

        then: "PRD 03 §7.1 字段集合"
        url.getTotal() == 150
        url.getFailures() == 6
        url.getFailureRate() == 6.0d / 150
        url.getMinDuration() == 10
        url.getMaxDuration() == 300
        url.getAvgDuration() == 7500.0d / 150
        url.getTp50() != null
        url.getTp90() != null
        url.getTp95() != null
        url.getTp99() != null
        url.getTp999() != null
        url.getTp9999() != null
        url.getQps() == 150.0d / 3600
    }

    def "Type 层的 QPS 使用 CAT 口径分母"() {
        when:
        def sql = service.typeTable("TRANSACTION", urlRows(), 3600).find { it.getType() == "SQL" }

        then:
        sql.getQps() == 200.0d / 3600
    }

    def "Type 层不产生 Name 维度的展开：每行只对应一个 Type"() {
        when:
        def table = service.typeTable("TRANSACTION", urlRows(), 3600)

        then:
        table.size() == 2
        table.every { it.getName() == null }
    }

    def "Type 汇总中同 Type 的多 Name 合并"() {
        when:
        def url = service.typeTable("TRANSACTION", urlRows(), 3600).find { it.getType() == "URL" }

        then: "100 + 50"
        url.getTotal() == 150
    }

    // ── Name 层 ──────────────────────────────────────────────

    def "Name 列表限定在指定 Type 内"() {
        when:
        def names = service.nameTable("TRANSACTION", "URL", urlRows(), 3600)

        then:
        names*.getName() as Set == ["/a", "/b"] as Set
        names.every { it.getType() == "URL" }
    }

    def "Name 层不包含其他 Type 的 Name"() {
        when:
        def names = service.nameTable("TRANSACTION", "URL", urlRows(), 3600)

        then: "SQL 的 s1 不应出现"
        !names*.getName().contains("s1")
    }

    def "Name 层字段与 Type 层一致"() {
        when:
        def a = service.nameTable("TRANSACTION", "URL", urlRows(), 3600).find { it.getName() == "/a" }

        then:
        a.getTotal() == 100
        a.getFailures() == 5
        a.getAvgDuration() == 50.0d
        a.getTp99() != null
        a.getQps() == 100.0d / 3600
    }

    def "Name 层没有该 Type 时返回空列表"() {
        expect:
        service.nameTable("TRANSACTION", "CACHE", urlRows(), 3600).isEmpty()
    }

    // ── Event：无耗时无分位 ──────────────────────────────────

    def "Event 表不提供耗时与分位（PRD 03 §8）"() {
        given:
        def rows = [
                row("order", SeriesKind.EVENT, "business", "e1", 100, 2, 0, 0, 0, []),
                row("order", SeriesKind.EVENT, "business", "e2", 50, 0, 0, 0, 0, [])
        ]

        when:
        def table = service.typeTable("EVENT", rows, 3600)

        then: "次数类与 QPS 有值"
        def business = table.find { it.getType() == "business" }
        business.getTotal() == 150
        business.getFailures() == 2
        business.getQps() == 150.0d / 3600

        and: "耗时与分位全部为空"
        business.getAvgDuration() == null
        business.getMinDuration() == 0
        business.getMaxDuration() == 0
        business.getTp50() == null
        business.getTp99() == null
        !business.hasDurationMetrics()
        !business.hasPercentiles()
    }

    def "Event 的 Name 列表同样不提供耗时与分位"() {
        given:
        def rows = [row("order", SeriesKind.EVENT, "business", "e1", 100, 0, 0, 0, 0, [])]

        when:
        def names = service.nameTable("EVENT", "business", rows, 3600)

        then:
        names[0].getTotal() == 100
        names[0].getTp99() == null
        names[0].getAvgDuration() == null
    }

    // ── Problem：异常无分位，慢类有分位 ──────────────────────

    def "异常类 Problem 不支持分位（PRD 03 §9）"() {
        given:
        def rows = [
                row("order", SeriesKind.PROBLEM, "EXCEPTION", "java.lang.NullPointerException",
                        30, 0, 0, 0, 0, [])
        ]

        when:
        def table = service.typeTable("PROBLEM", rows, 3600)

        then:
        def row0 = table[0]
        row0.getType() == "EXCEPTION"
        row0.getTotal() == 30
        row0.getTp99() == null
        row0.getAvgDuration() == null
        !row0.hasPercentiles()
    }

    def "慢类 Problem 支持分位"() {
        given:
        def rows = [
                row("order", SeriesKind.PROBLEM, "SLOW_SQL", "select_order",
                        10, 0, 50000, 3000, 8000, (1..10).collect { 5000L })
        ]

        when:
        def table = service.typeTable("PROBLEM", rows, 3600)

        then:
        def row0 = table[0]
        row0.getType() == "SLOW_SQL"
        row0.getTotal() == 10
        row0.getTp99() != null
        row0.getAvgDuration() == 5000.0d
        row0.hasPercentiles()
    }

    def "Problem 五类都能在 Type 表中出现"() {
        given:
        def rows = [
                row("order", SeriesKind.PROBLEM, "EXCEPTION", "ex", 1, 0, 0, 0, 0, []),
                row("order", SeriesKind.PROBLEM, "SLOW_URL", "u", 1, 0, 2000, 2000, 2000, [2000L]),
                row("order", SeriesKind.PROBLEM, "SLOW_SQL", "s", 1, 0, 200, 200, 200, [200L]),
                row("order", SeriesKind.PROBLEM, "SLOW_CALL", "c", 1, 0, 2000, 2000, 2000, [2000L]),
                row("order", SeriesKind.PROBLEM, "SLOW_CACHE", "k", 1, 0, 100, 100, 100, [100L])
        ]

        when:
        def table = service.typeTable("PROBLEM", rows, 3600)

        then:
        table*.getType() as Set == ["EXCEPTION", "SLOW_URL", "SLOW_SQL", "SLOW_CALL", "SLOW_CACHE"] as Set
    }

    // ── 边界 ─────────────────────────────────────────────────

    def "空输入返回空表"() {
        expect:
        service.typeTable("TRANSACTION", [], 3600).isEmpty()
        service.nameTable("TRANSACTION", "URL", [], 3600).isEmpty()
    }

    def "无调用时次数类为 0、耗时类无值"() {
        given:
        def rows = [row("order", SeriesKind.TRANSACTION, "URL", "/a", 0, 0, 0, 0, 0, [])]

        when:
        def url = service.typeTable("TRANSACTION", rows, 3600)[0]

        then:
        url.getTotal() == 0
        url.getAvgDuration() == null
        url.getTp99() == null
        url.getQps() == 0.0d
    }

    @Unroll
    def "Type 表按总量降序排列（便于值班人员先看大头）"() {
        expect:
        def table = service.typeTable("TRANSACTION", urlRows(), 3600)
        table[0].getTotal() >= table[1].getTotal()

        where:
        _ << [1]
    }

    def "QPS 分母为 0 时 QPS 无值"() {
        when:
        def url = service.typeTable("TRANSACTION", urlRows(), 0)[0]

        then:
        url.getQps() == null
    }
}
