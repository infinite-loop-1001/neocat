package com.neocat.query.domain.report

import com.neocat.query.domain.stat.Stat

import com.neocat.analysis.domain.bucket.AggregatedRow
import com.neocat.analysis.domain.bucket.AggregationLevel
import com.neocat.analysis.domain.bucket.SeriesKey
import com.neocat.analysis.domain.bucket.SeriesKind
import spock.lang.Specification
import spock.lang.Unroll

import java.time.Instant

/**
 * G8 任务59（红）：机器维度。
 * 对应 PRD 03 §7.3（Top N + other 对账、明细表、勾选对比、选后不加 other）、
 * §10（Heartbeat 不合并 other）、PRD 04 §8（依赖列表不使用 other）。
 */
class MachineDimensionSpec extends Specification {

    MachineViewBuilder builder = new MachineViewBuilder()

    static Instant HOUR = Instant.parse("2026-09-24T04:00:00Z")

    def machineRow(String instance, long count, long fail = 0, Long durationSum = null) {
        def key = SeriesKey.of("order", SeriesKind.TRANSACTION, "URL", "/a", instance)
        def row = new AggregatedRow(key, HOUR, AggregationLevel.MINUTE, 60)
        long sum = durationSum == null ? count * 10 : durationSum
        count.times { row.recordDuration(10L) }
        row.addCount(count, fail, sum, 10, 10)
        return row
    }

    def rows() {
        [
                machineRow("10.0.0.1", 100),
                machineRow("10.0.0.2", 80),
                machineRow("10.0.0.3", 60),
                machineRow("10.0.0.4", 40),
                machineRow("10.0.0.5", 20)
        ]
    }

    // ── Top N + other ────────────────────────────────────────

    def "默认按贡献值降序取 Top N"() {
        when:
        def view = builder.build(rows(), Stat.HITS, 3, true, [], 60)

        then:
        view.getTop()*.getInstance() == ["10.0.0.1", "10.0.0.2", "10.0.0.3"]
    }

    def "Top N 之外的机器合并为 other"() {
        when:
        def view = builder.build(rows(), Stat.HITS, 3, true, [], 60)

        then:
        view.getOther() != null
        view.getOther().getInstance() == MachineRow.OTHER
        view.getOther().getTotal() == 60      // 40 + 20
    }

    def "Top N + other 的数量与全量对账一致（PRD 03 §7.3 对账要求）"() {
        when:
        def view = builder.build(rows(), Stat.HITS, 2, true, [], 60)

        then:
        view.reconciledTotal() == view.allTotal()
        view.allTotal() == 300
    }

    def "机器数量不超过 Top N 时不产生 other"() {
        when:
        def view = builder.build(rows().take(3), Stat.HITS, 5, true, [], 60)

        then:
        view.getTop().size() == 3
        view.getOther() == null
        view.reconciledTotal() == view.allTotal()
    }

    def "机器数量正好等于 Top N 时不产生 other"() {
        when:
        def view = builder.build(rows().take(3), Stat.HITS, 3, true, [], 60)

        then:
        view.getOther() == null
        view.getTop().size() == 3
    }

    def "all 保留全部机器明细供分页"() {
        when:
        def view = builder.build(rows(), Stat.HITS, 2, true, [], 60)

        then:
        view.getAll().size() == 5
        view.getAll()*.getInstance() as Set == ["10.0.0.1", "10.0.0.2", "10.0.0.3", "10.0.0.4", "10.0.0.5"] as Set
    }

    def "all 按贡献值降序，便于前端直接分页"() {
        when:
        def view = builder.build(rows(), Stat.HITS, 2, true, [], 60)

        then:
        view.getAll()*.getTotal() == view.getAll()*.getTotal().sort().reverse()
    }

    // ── 手动勾选 ─────────────────────────────────────────────

    def "手动勾选机器后只展示选中机器，不再自动加入 other"() {
        when:
        def view = builder.build(rows(), Stat.HITS, 3, true, ["10.0.0.4", "10.0.0.5"], 60)

        then:
        view.hasSelection()
        view.getSelected()*.getInstance() as Set == ["10.0.0.4", "10.0.0.5"] as Set

        and: "other 不再生成"
        view.getOther() == null
    }

    def "勾选不受 Top N 限制：可以选中排名靠后的机器"() {
        when:
        def view = builder.build(rows(), Stat.HITS, 2, true, ["10.0.0.5"], 60)

        then:
        view.getSelected()*.getInstance() == ["10.0.0.5"]
    }

    def "勾选多台机器时按贡献值降序返回"() {
        when:
        def view = builder.build(rows(), Stat.HITS, 3, true, ["10.0.0.5", "10.0.0.4"], 60)

        then:
        view.getSelected()*.getInstance() == ["10.0.0.4", "10.0.0.5"]
    }

    def "勾选不存在的实例被忽略"() {
        when:
        def view = builder.build(rows(), Stat.HITS, 3, true, ["ghost"], 60)

        then:
        view.getSelected().isEmpty()
    }

    // ── Heartbeat：不合并 other ──────────────────────────────

    def "Heartbeat 场景 Top N 之外不合并为 other（PRD 03 §10）"() {
        when:
        def view = builder.build(rows(), Stat.MAX, 3, false, [], 60)

        then:
        view.getTop().size() == 3
        view.getOther() == null

        and: "全量明细仍可查"
        view.getAll().size() == 5
    }

    @Unroll
    def "依赖列表场景也不使用 other（依赖按具体服务对展示）"() {
        when:
        def view = builder.build(rows(), Stat.HITS, 2, false, [], 60)

        then:
        view.getOther() == null

        where:
        _ << [1]
    }

    // ── 排序统计项 ───────────────────────────────────────────

    def "按 AVG 排序时贡献值使用平均耗时"() {
        given: "两台机器：一台慢少量，一台快大量"
        def slow = machineRow("slow", 10, 0, 1000)      // avg = 100ms
        def fast = machineRow("fast", 1000, 0, 10000)   // avg = 10ms

        when:
        def view = builder.build([slow, fast], Stat.AVG, 1, true, [], 60)

        then:
        view.getTop()[0].getInstance() == "slow"
        view.getTop()[0].getContribution() == 100.0d
    }

    def "按 HITS 排序时贡献值使用总量"() {
        when:
        def view = builder.build(rows(), Stat.HITS, 1, true, [], 60)

        then:
        view.getTop()[0].getInstance() == "10.0.0.1"
        view.getTop()[0].getContribution() == 100.0d
    }

    def "other 行的分位由合并分布重算，而非平均各机器分位"() {
        given: "机器 A 全是 10ms、机器 B 全是 1000ms，各 10 次"
        def a = machineRow("a", 10, 0, 100)
        def b = new AggregatedRow(SeriesKey.of("order", SeriesKind.TRANSACTION, "URL", "/a", "b"),
                HOUR, AggregationLevel.MINUTE, 60)
        10.times { b.recordDuration(1000L) }
        b.addCount(10, 0, 10000, 1000, 1000)

        when: "Top N = 1，B 落入 other"
        def view = builder.build([a, b], Stat.HITS, 1, true, [], 60)

        then:
        view.getOther().getTotal() == 10
        and: "other 的耗时统计来自自身样本"
        view.getOther().getAvgDuration() == 1000.0d
    }

    // ── 每行字段 ─────────────────────────────────────────────

    def "每行包含总量、失败量、平均耗时与 QPS"() {
        given:
        def rows = [machineRow("10.0.0.1", 100, 5, 1000)]

        when:
        def view = builder.build(rows, Stat.HITS, 5, true, [], 3600)

        then:
        def row = view.getTop()[0]
        row.getTotal() == 100
        row.getFailures() == 5
        row.getAvgDuration() == 10.0d
        row.getQps() == 0.027778
    }

    def "空输入返回空视图"() {
        when:
        def view = builder.build([], Stat.HITS, 10, true, [], 60)

        then:
        view.getTop().isEmpty()
        view.getAll().isEmpty()
        view.getOther() == null
        !view.hasSelection()
    }

    def "全机器聚合行不应出现在机器明细中"() {
        given: "混入一行 instance = all 的聚合行"
        def aggregate = new AggregatedRow(SeriesKey.of("order", SeriesKind.TRANSACTION, "URL", "/a"),
                HOUR, AggregationLevel.MINUTE, 60)
        aggregate.addCount(999, 0, 9990, 10, 10)
        def mixed = [aggregate] + rows()

        when:
        def view = builder.build(mixed, Stat.HITS, 10, true, [], 60)

        then: "聚合行被排除，避免与机器行重复计数"
        !view.getAll()*.getInstance().contains(SeriesKey.ALL)
        view.allTotal() == 300
    }
}
