package com.neocat.dashboard.domain.card

import com.neocat.query.domain.stat.Stat
import spock.lang.Specification
import spock.lang.Unroll

/**
 * G9 任务71（红）：维度下钻与阈值线。
 * 对应 PRD 05 §6（机器维度：默认全机器聚合、下钻只用于诊断不改默认目标）、
 * §7（阈值线：作用于聚合结果、下钻不自动变成逐机器阈值线、不自动改告警阈值）、
 * §11（验收 6）。
 */
class CardDimensionSpec extends Specification {

    CardDimensionService service = new CardDimensionService()

    def card() {
        return Card.withoutThresholds(1L, 1L, "order", "TRANSACTION", "URL", "/a", null, [], "failures / hits",
                "RECENT_24H", 0)
    }

    def machinePoints(String instance, double p95, double p99) {
        return [
                new CardPoint(1000L, 2000L, p95, CardPointOutcome.OK, []),
                new CardPoint(2000L, 3000L, p99, CardPointOutcome.OK, [])
        ]
    }

    // ── 默认聚合 ─────────────────────────────────────────────

    def "默认展示全部机器聚合结果"() {
        given:
        def request = new CardDrillRequest(1L, [], 10, [])

        when:
        def view = service.view(card(), request, aggregatePoints(), [:])

        then:
        !view.isDrilled()
        view.getAggregated().size() == 2
        view.getByInstance().isEmpty()
    }

    def "聚合视图不返回机器明细"() {
        given:
        def request = new CardDrillRequest(1L, [], 10, [])

        when:
        def view = service.view(card(), request, aggregatePoints(), [:])

        then:
        view.getByInstance().isEmpty()
    }

    // ── 下钻 ─────────────────────────────────────────────────

    def "勾选机器后进入下钻模式并返回各机器结果"() {
        given:
        def request = new CardDrillRequest(1L, ["10.0.0.8", "10.0.0.9"], 10, [])
        def instanceData = [
                "10.0.0.8": machinePoints("10.0.0.8", 0.01d, 0.02d),
                "10.0.0.9": machinePoints("10.0.0.9", 0.03d, 0.04d)
        ]

        when:
        def view = service.view(card(), request, aggregatePoints(), instanceData)

        then:
        view.isDrilled()
        view.getByInstance()*.getInstance() as Set == ["10.0.0.8", "10.0.0.9"] as Set
    }

    def "下钻不改变默认聚合结果：聚合序列仍然返回"() {
        given:
        def request = new CardDrillRequest(1L, ["10.0.0.8"], 10, [])
        def instanceData = ["10.0.0.8": machinePoints("10.0.0.8", 0.01d, 0.02d)]

        when:
        def view = service.view(card(), request, aggregatePoints(), instanceData)

        then: "聚合结果照常可用，便于「返回全部机器聚合」"
        view.getAggregated() == aggregatePoints()
    }

    def "下钻只用于诊断：请求对象不影响卡片默认目标"() {
        given:
        def originalCard = card()
        def request = new CardDrillRequest(1L, ["10.0.0.8", "10.0.0.9"], 5, [])

        when:
        service.view(originalCard, request, aggregatePoints(), [:])

        then: "卡片目标字段未被修改"
        originalCard.getTargetType() == "URL"
        originalCard.getTargetName() == "/a"
        originalCard.getFormula() == "failures / hits"
    }

    def "全机器聚合行不会作为一台机器出现在下钻结果中"() {
        given:
        def request = new CardDrillRequest(1L, ["10.0.0.8"], 10, [])
        def instanceData = [
                "10.0.0.8": machinePoints("10.0.0.8", 0.01d, 0.02d),
                "all"     : machinePoints("all", 0.02d, 0.03d)
        ]

        when:
        def view = service.view(card(), request, aggregatePoints(), instanceData)

        then:
        !view.getByInstance()*.getInstance().contains("all")
    }

    def "勾选不存在的机器时返回空结果而不报错"() {
        given:
        def request = new CardDrillRequest(1L, ["ghost"], 10, [])

        when:
        def view = service.view(card(), request, aggregatePoints(), ["10.0.0.8": machinePoints("10.0.0.8", 0.01d, 0.02d)])

        then:
        noExceptionThrown()
        view.getByInstance().isEmpty()
    }

    def "Top N 限制返回的机器数量"() {
        given:
        def request = new CardDrillRequest(1L, [], 2, [])
        def instanceData = [
                "10.0.0.1": machinePoints("10.0.0.1", 0.01d, 0.02d),
                "10.0.0.2": machinePoints("10.0.0.2", 0.01d, 0.02d),
                "10.0.0.3": machinePoints("10.0.0.3", 0.01d, 0.02d)
        ]

        when:
        def view = service.view(card(), request, aggregatePoints(), instanceData)

        then:
        view.getByInstance().size() <= 2
    }

    // ── 阈值线 ───────────────────────────────────────────────

    def "阈值线作用于卡片全部机器聚合结果"() {
        given:
        def lines = [new ThresholdLine(ThresholdDirection.ABOVE, 0.02d)]
        def request = new CardDrillRequest(1L, [], 10, lines)

        when:
        def view = service.view(card(), request, aggregatePoints(), [:])

        then:
        view.getThresholdLines() == lines
        view.breaches() == 1        // 0.03 超过 0.02
    }

    def "下钻不产生逐机器阈值线"() {
        given:
        def lines = [new ThresholdLine(ThresholdDirection.ABOVE, 0.02d)]
        def request = new CardDrillRequest(1L, ["10.0.0.8", "10.0.0.9"], 10, lines)
        def instanceData = [
                "10.0.0.8": machinePoints("10.0.0.8", 0.05d, 0.06d),
                "10.0.0.9": machinePoints("10.0.0.9", 0.07d, 0.08d)
        ]

        when:
        def view = service.view(card(), request, aggregatePoints(), instanceData)

        then: "阈值线仍只作用于聚合结果，没有按机器展开"
        view.getThresholdLines().size() == 1
        view.isDrilled()
        and: "越线计数只基于聚合序列，不基于各机器"
        view.breaches() == 1
    }

    def "低于方向的阈值线同样生效"() {
        given:
        def lines = [new ThresholdLine(ThresholdDirection.BELOW, 0.01d)]
        def request = new CardDrillRequest(1L, [], 10, lines)

        when:
        def view = service.view(card(), request, aggregatePoints(), [:])

        then: "0.01 不低于 0.01（严格小于才算越线）"
        view.breaches() == 0
    }

    def "缺口点不参与阈值线越线计数"() {
        given:
        def points = [
                new CardPoint(1000L, 2000L, null, CardPointOutcome.GAP, ["hits"]),
                new CardPoint(2000L, 3000L, 0.05d, CardPointOutcome.OK, [])
        ]
        def lines = [new ThresholdLine(ThresholdDirection.ABOVE, 0.02d)]
        def request = new CardDrillRequest(1L, [], 10, lines)

        when:
        def view = service.view(card(), request, points, [:])

        then:
        view.breaches() == 1
    }

    def "不可计算点不参与阈值线越线计数"() {
        given:
        def points = [
                new CardPoint(1000L, 2000L, null, CardPointOutcome.DIVIDE_BY_ZERO, []),
                new CardPoint(2000L, 3000L, null, CardPointOutcome.GAP, [])
        ]
        def request = new CardDrillRequest(1L, [], 10, [new ThresholdLine(ThresholdDirection.ABOVE, 0.01d)])

        when:
        def view = service.view(card(), request, points, [:])

        then:
        view.breaches() == 0
    }

    def "没有阈值线时越线计数为 0"() {
        given:
        def request = new CardDrillRequest(1L, [], 10, [])

        when:
        def view = service.view(card(), request, aggregatePoints(), [:])

        then:
        view.breaches() == 0
    }

    def "阈值线不携带告警语义：数据结构上不含比较条件与收件人"() {
        when:
        def line = new ThresholdLine(ThresholdDirection.ABOVE, 0.02d)

        then: "只有方向与数值"
        line.getDirection() == ThresholdDirection.ABOVE
        line.getValue() == 0.02d
        and: "ThresholdLine 不含任何告警字段"
        ThresholdLine.declaredFields*.name as Set == ["direction", "value"] as Set
    }

    @Unroll
    def "阈值线方向：#direction"() {
        expect:
        ThresholdDirection.values()*.name() as Set == ["ABOVE", "BELOW"] as Set

        where:
        direction << [ThresholdDirection.ABOVE]
    }

    def aggregatePoints() {
        return [
                new CardPoint(1000L, 2000L, 0.01d, CardPointOutcome.OK, []),
                new CardPoint(2000L, 3000L, 0.03d, CardPointOutcome.OK, [])
        ]
    }
}
