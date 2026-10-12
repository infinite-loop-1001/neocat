package com.neocat.alert.domain.rule

import com.neocat.alert.domain.AlertRuleService
import com.neocat.alert.domain.entity.AlertRule
import com.neocat.alert.domain.entity.vo.rule.AlertChannel
import com.neocat.alert.domain.entity.vo.rule.AlertScope
import com.neocat.alert.domain.entity.vo.rule.AlertTarget
import com.neocat.alert.domain.entity.vo.rule.Combinator
import com.neocat.alert.domain.entity.vo.rule.Comparator
import com.neocat.alert.domain.entity.vo.rule.Condition
import com.neocat.query.domain.stat.Stat
import spock.lang.Specification
import spock.lang.Unroll

import static com.neocat.alert.domain.entity.vo.rule.AlertChannel.*

/**
 * G10 任务75（红）：告警规则模型。
 * 对应 PRD 06 §2（条件模型：一个目标、统一 AND/OR、X 属整条规则、不支持的形式）、
 * §3.1（新建：保存后关闭）、§12（验收 1、4、5）。
 */
class AlertRuleSpec extends Specification {

    AlertRuleService service = new AlertRuleService()

    def target() {
        return AlertTarget.rawMetric("order", "TRANSACTION", "URL", "POST /orders")
    }

    def condition(Stat stat, Comparator comparator, BigDecimal threshold) {
        return new Condition(stat, comparator, threshold)
    }

    // ── 保存后关闭 ───────────────────────────────────────────

    def "新建规则保存后为关闭状态"() {
        when:
        def rule = service.save(AlertRule.draft(AlertScope.SERVICE, null, "订单失败率",
                "", target(), Combinator.AND, 3,
                [condition(Stat.FAILURE_RATE, Comparator.GT, 0.05)],
                [1L, 2L], [EMAIL]))

        then:
        !rule.isEnabled()
        !rule.isInvalid()
        rule.getStateSince() == null
    }

    def "保存不会发送通知（保存动作本身不产生任何通知副作用）"() {
        given:
        def notifier = Mock(Notifier)
        def s = new AlertRuleService(notifier)

        when:
        s.save(AlertRule.draft(AlertScope.SERVICE, null, "r", "", target(), Combinator.AND, 1,
                [condition(Stat.HITS, Comparator.GT, 10.0)], [1L], [EMAIL]))

        then:
        0 * notifier._
    }

    // ── 一条规则一个目标 ─────────────────────────────────────

    def "一条规则只绑定一个目标序列"() {
        when:
        def rule = service.save(AlertRule.draft(AlertScope.SERVICE, null, "r", "", target(),
                Combinator.AND, 2,
                [condition(Stat.HITS, Comparator.GT, 10.0),
                 condition(Stat.FAILURE_RATE, Comparator.GT, 0.1)],
                [1L], [EMAIL]))

        then: "两个条件作用于同一目标"
        rule.getConditions().size() == 2
        rule.getTarget() == target()
    }

    def "规则不允许持有多个目标：数据结构上只有一个 target 字段"() {
        expect:
        AlertRule.declaredFields*.name.count { it == "target" } == 1
    }

    // ── 统一 AND / OR ────────────────────────────────────────

    @Unroll
    def "连接符为统一 #combinator"() {
        when:
        def rule = service.save(AlertRule.draft(AlertScope.SERVICE, null, "r", "", target(),
                combinator, 1,
                [condition(Stat.HITS, Comparator.GT, 1.0), condition(Stat.FAILURES, Comparator.GT, 0.0)],
                [1L], [EMAIL]))

        then:
        rule.getCombinator() == combinator

        where:
        combinator << [Combinator.AND, Combinator.OR]
    }

    def "结构上无法为各条件配置不同连接符（连接符是规则级字段）"() {
        expect:
        AlertRule.declaredFields*.name.contains("combinator")
        and: "Condition 不含连接符字段"
        !Condition.declaredFields*.name.contains("combinator")
    }

    def "结构上无法为各条件配置不同连续点数（X 是规则级字段）"() {
        expect:
        AlertRule.declaredFields*.name.contains("windowPoints")
        and:
        !Condition.declaredFields*.name.contains("windowPoints")
        !Condition.declaredFields*.name.contains("consecutiveMinutes")
    }

    // ── 窗口长度校验 ─────────────────────────────────────────

    def "窗口长度至少为 1"() {
        when:
        service.save(AlertRule.draft(AlertScope.SERVICE, null, "r", "", target(),
                Combinator.AND, 0, [condition(Stat.HITS, Comparator.GT, 1.0)], [1L], [EMAIL]))

        then:
        thrown(IllegalArgumentException)
    }

    def "窗口长度上限校验（避免长时间窗口导致无意义等待）"() {
        when:
        service.save(AlertRule.draft(AlertScope.SERVICE, null, "r", "", target(),
                Combinator.AND, 1441, [condition(Stat.HITS, Comparator.GT, 1.0)], [1L], [EMAIL]))

        then:
        thrown(IllegalArgumentException)
    }

    // ── 条件校验 ─────────────────────────────────────────────

    def "至少需要一个条件"() {
        when:
        service.save(AlertRule.draft(AlertScope.SERVICE, null, "r", "", target(),
                Combinator.AND, 1, [], [1L], [EMAIL]))

        then:
        thrown(IllegalArgumentException)
    }

    def "组织告警必须指定叶子组织"() {
        when:
        service.save(AlertRule.draft(AlertScope.ORGANIZATION, null, "r", "", target(),
                Combinator.AND, 1, [condition(Stat.HITS, Comparator.GT, 1.0)], [], [EMAIL]))

        then:
        thrown(IllegalArgumentException)
    }

    def "服务告警不绑定组织"() {
        when:
        def rule = service.save(AlertRule.draft(AlertScope.SERVICE, null, "r", "", target(),
                Combinator.AND, 1, [condition(Stat.HITS, Comparator.GT, 1.0)], [1L], [EMAIL]))

        then:
        rule.getOrgId() == null
        !rule.isOrganization()
    }

    // ── 不支持的形式 ─────────────────────────────────────────

    def "一期不支持环比告警：不存在环比相关字段"() {
        expect:
        !AlertRule.declaredFields*.name.any {
            it.toLowerCase().contains("mom")
                    || it.toLowerCase().contains("compare")
        }
    }

    def "一期不支持恢复通知、静默窗口、确认状态与严重度"() {
        expect: "结构上不存在这些字段"
        def fields = AlertRule.declaredFields*.name
        !fields.any { it.toLowerCase().contains("severity") }
        !fields.any { it.toLowerCase().contains("ack") }
        !fields.any { it.toLowerCase().contains("silence") }
        !fields.any { it.toLowerCase().contains("recover") }
    }

    def "一期不支持告警事件历史：规则内不保存任何触发记录"() {
        expect:
        !AlertRule.declaredFields*.name.any {
            it.toLowerCase().contains("history")
                    || it.toLowerCase().contains("trigger")
        }
    }

    // ── 条件匹配语义 ─────────────────────────────────────────

    @Unroll
    def "条件 #comparator #threshold 对比值 #value → #expected"() {
        given:
        def c = condition(Stat.HITS, comparator, threshold)

        expect:
        c.matches(value) == expected

        where:
        comparator     | threshold | value | expected
        Comparator.GT  | 10.0      | 11.0  | true
        Comparator.GT  | 10.0      | 10.0  | false
        Comparator.GTE | 10.0      | 10.0  | true
        Comparator.LT  | 10.0      | 9.0   | true
        Comparator.LT  | 10.0      | 10.0  | false
        Comparator.LTE | 10.0      | 10.0  | true
        Comparator.EQ  | 10.0      | 10.0  | true
        Comparator.NEQ | 10.0      | 9.0   | true
    }

    def "缺数不满足任何条件（未知点既不高于也不低于）"() {
        expect: "PRD 06 §6：未知点不满足『高于』或『低于』任何条件"
        !condition(Stat.HITS, Comparator.GT, 0.0).matches(null)
        !condition(Stat.HITS, Comparator.LT, 100.0).matches(null)
        !condition(Stat.HITS, Comparator.GTE, 0.0).matches(null)
        !condition(Stat.HITS, Comparator.LTE, 100.0).matches(null)
        !condition(Stat.HITS, Comparator.EQ, 0.0).matches(null)
        !condition(Stat.HITS, Comparator.NEQ, 0.0).matches(null)
    }

    def "零值可以满足条件（完整且确认无调用时次数类为 0）"() {
        expect: "PRD 06 §6：完整且确认无调用的次数类点可以是 0"
        condition(Stat.HITS, Comparator.LTE, 0.0).matches(0.0)
        condition(Stat.HITS, Comparator.EQ, 0.0).matches(0.0)
        !condition(Stat.HITS, Comparator.GT, 0.0).matches(0.0)
    }

    // ── 通道 ─────────────────────────────────────────────────

    def "一期通道只有邮件、钉钉、飞书"() {
        expect:
        AlertChannel.values()*.name() as Set == ["EMAIL", "DINGTALK", "FEISHU"] as Set
    }

    def "站内预告警不是通道：枚举中不存在 INBOX 或 SITE"() {
        expect:
        !AlertChannel.values()*.name().any { it in ["INBOX", "SITE", "INTERNAL"] }
    }

    // ── 引用统计项 ───────────────────────────────────────────

    def "规则依赖取公式与条件的有序去重并集，引用检查仍只看公式"() {
        given:
        def formulaStats = [Stat.AVG, Stat.HITS, Stat.AVG]
        def conditions = [condition(Stat.HITS, Comparator.GT, 1.0),
                          condition(Stat.FAILURES, Comparator.GT, 0.0),
                          condition(Stat.FAILURES, Comparator.LT, 10.0)]
        def cardTarget = AlertTarget.cardResult(12L, "order", "TRANSACTION", "URL", "/a", formulaStats)
        def rule = AlertRule.draft(AlertScope.ORGANIZATION, 7L, "r", "", cardTarget,
                Combinator.AND, 1, conditions, [1L], [EMAIL])

        expect:
        rule.requiredStats() == [Stat.AVG, Stat.HITS, Stat.FAILURES]
        rule.referencedStats() == formulaStats
        rule.getTarget().getFormulaStats() == formulaStats
        rule.getConditions() == conditions

        when: "调用方自行复制后筛选，不反向修改聚合或后续收集结果"
        def selectedStats = new ArrayList<>(rule.requiredStats())
        selectedStats.remove(Stat.AVG)

        then:
        selectedStats == [Stat.HITS, Stat.FAILURES]
        rule.requiredStats() == [Stat.AVG, Stat.HITS, Stat.FAILURES]
        rule.referencedStats() == formulaStats
        rule.getTarget().getFormulaStats() == formulaStats
        rule.getConditions() == conditions
    }

    def "规则依赖是快照，源列表后续修改不影响已返回的列表"() {
        given:
        def formulaStats = [Stat.AVG, Stat.AVG]
        def conditions = [condition(Stat.HITS, Comparator.GT, 1.0)]
        def suppliedTarget = new AlertTarget(null, 12L, "order", "TRANSACTION", "URL", "/a", null, formulaStats)
        def rule = new AlertRule(1L, AlertScope.SERVICE, null, "r", "", suppliedTarget,
                Combinator.AND, 1, conditions, [], [], false, false, null)
        def first = new ArrayList<>(rule.requiredStats())
        def second = rule.requiredStats()

        when:
        formulaStats.add(Stat.FAILURES)
        conditions.add(condition(Stat.FAILURE_RATE, Comparator.GT, 0.05))
        first.remove(0)

        then:
        first == [Stat.HITS]
        second == [Stat.AVG, Stat.HITS]
        rule.requiredStats() == [Stat.AVG, Stat.FAILURES, Stat.HITS, Stat.FAILURE_RATE]
        formulaStats == [Stat.AVG, Stat.AVG, Stat.FAILURES]
        conditions*.getStat() == [Stat.HITS, Stat.FAILURE_RATE]
    }

    @Unroll
    def "规则依赖兼容尚未完整装配的目标与条件：#caseName"() {
        given:
        def rule = new AlertRule(1L, AlertScope.SERVICE, null, "r", "", suppliedTarget,
                Combinator.AND, 1, suppliedConditions, [], [], false, false, null)

        expect:
        rule.requiredStats() == expected

        when: "调用方复制后加工，空结果也不影响后续调用"
        def result = new ArrayList<>(rule.requiredStats())
        result.add(Stat.FAILURES)

        then:
        result == expected + [Stat.FAILURES]
        rule.requiredStats() == expected

        where:
        caseName              | suppliedTarget                                                               | suppliedConditions                            | expected
        "都未设置"            | null                                                                         | null                                          | []
        "仅条件"              | null                                                                         | [new Condition(Stat.HITS, Comparator.GT, 1G)] | [Stat.HITS]
        "公式未设置"          | new AlertTarget(null, 0L, "order", "TRANSACTION", "URL", "/a", null, null)   | [new Condition(Stat.HITS, Comparator.GT, 1G)] | [Stat.HITS]
        "仅公式"              | AlertTarget.cardResult(12L, "order", "TRANSACTION", "URL", "/a", [Stat.AVG]) | null                                          | [Stat.AVG]
        "公式为空、条件未设置" | AlertTarget.rawMetric("order", "TRANSACTION", "URL", "/a")                   | null                                          | []
        "目标未设置、条件为空" | null                                                                         | []                                            | []
        "空集合"              | AlertTarget.rawMetric("order", "TRANSACTION", "URL", "/a")                   | []                                            | []
    }

    @Unroll
    def "规则依赖跳过空统计项并对公式与条件整体去重：#caseName"() {
        given:
        def suppliedTarget = new AlertTarget(null, 0L, "order", "TRANSACTION", "URL", "/a", null, formulaStats)
        def rule = new AlertRule(1L, AlertScope.SERVICE, null, "r", "", suppliedTarget,
                Combinator.AND, 1, conditions, [], [], false, false, null)

        expect:
        rule.requiredStats() == expected
        rule.getTarget().getFormulaStats() == formulaStats
        rule.getConditions() == conditions

        where:
        caseName             | formulaStats            | conditions                                                                                 | expected
        "公式含空项"         | [null, Stat.HITS]       | []                                                                                         | [Stat.HITS]
        "条件统计项为空"     | [Stat.HITS]             | [new Condition(null, Comparator.GT, 1G)]                                                   | [Stat.HITS]
        "公式空项重复"       | [null, Stat.HITS, null] | [new Condition(null, Comparator.GT, 1G)]                                                   | [Stat.HITS]
        "条件空项重复"       | null                    | [new Condition(null, Comparator.GT, 1G), new Condition(null, Comparator.LT, 2G)]           | []
        "仅条件且统计项重复" | []                      | [new Condition(Stat.HITS, Comparator.GT, 1G), new Condition(Stat.HITS, Comparator.LT, 2G)] | [Stat.HITS]
        "公式重复项去重"     | [Stat.HITS, Stat.HITS]  | [new Condition(Stat.HITS, Comparator.GT, 1G)]                                              | [Stat.HITS]
    }

    def "规则依赖收集不吞掉空条件对象"() {
        given:
        def rule = new AlertRule(1L, AlertScope.SERVICE, null, "r", "", target(),
                Combinator.AND, 1, [null], [], [], false, false, null)

        when:
        rule.requiredStats()

        then:
        thrown(NullPointerException)
    }

    def "原始指标规则引用条件中的统计项"() {
        when:
        def rule = AlertRule.draft(AlertScope.SERVICE, null, "r", "", target(), Combinator.AND, 1,
                [condition(Stat.FAILURE_RATE, Comparator.GT, 0.1), condition(Stat.HITS, Comparator.GT, 10.0)],
                [1L], [EMAIL])

        then:
        rule.referencedStats() as Set == [Stat.FAILURE_RATE, Stat.HITS] as Set
    }

    def "卡片结果规则引用卡片公式的统计项"() {
        when:
        def cardTarget = AlertTarget.cardResult(12L, "order", "TRANSACTION", "URL", "/a",
                [Stat.FAILURES, Stat.HITS])
        def rule = AlertRule.draft(AlertScope.ORGANIZATION, 7L, "r", "", cardTarget, Combinator.AND, 1,
                [condition(Stat.FAILURE_RATE, Comparator.GT, 0.1)], [1L], [EMAIL])

        then:
        rule.referencedStats() as Set == [Stat.FAILURES, Stat.HITS] as Set
        rule.getTarget().isCardResult()
    }

    def "作用范围枚举完整覆盖两类告警"() {
        expect:
        AlertScope.values()*.name() as Set == ["SERVICE", "ORGANIZATION"] as Set
    }
}
