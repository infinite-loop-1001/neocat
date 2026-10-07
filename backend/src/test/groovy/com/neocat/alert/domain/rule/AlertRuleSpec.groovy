package com.neocat.alert.domain.rule

import com.neocat.alert.domain.engine.Notifier

import com.neocat.query.domain.stat.Stat
import spock.lang.Specification
import spock.lang.Unroll

import static com.neocat.alert.domain.rule.AlertChannel.*

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

    def condition(Stat stat, Comparator comparator, double threshold) {
        return new Condition(stat, comparator, threshold)
    }

    // ── 保存后关闭 ───────────────────────────────────────────

    def "新建规则保存后为关闭状态"() {
        when:
        def rule = service.save(AlertRule.draft(AlertScope.SERVICE, null, "订单失败率",
                "", target(), Combinator.AND, 3,
                [condition(Stat.FAILURE_RATE, Comparator.GT, 0.05d)],
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
                [condition(Stat.HITS, Comparator.GT, 10d)], [1L], [EMAIL]))

        then:
        0 * notifier._
    }

    // ── 一条规则一个目标 ─────────────────────────────────────

    def "一条规则只绑定一个目标序列"() {
        when:
        def rule = service.save(AlertRule.draft(AlertScope.SERVICE, null, "r", "", target(),
                Combinator.AND, 2,
                [condition(Stat.HITS, Comparator.GT, 10d),
                 condition(Stat.FAILURE_RATE, Comparator.GT, 0.1d)],
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
                [condition(Stat.HITS, Comparator.GT, 1d), condition(Stat.FAILURES, Comparator.GT, 0d)],
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
                Combinator.AND, 0, [condition(Stat.HITS, Comparator.GT, 1d)], [1L], [EMAIL]))

        then:
        thrown(IllegalArgumentException)
    }

    def "窗口长度上限校验（避免长时间窗口导致无意义等待）"() {
        when:
        service.save(AlertRule.draft(AlertScope.SERVICE, null, "r", "", target(),
                Combinator.AND, 1441, [condition(Stat.HITS, Comparator.GT, 1d)], [1L], [EMAIL]))

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
                Combinator.AND, 1, [condition(Stat.HITS, Comparator.GT, 1d)], [], [EMAIL]))

        then:
        thrown(IllegalArgumentException)
    }

    def "服务告警不绑定组织"() {
        when:
        def rule = service.save(AlertRule.draft(AlertScope.SERVICE, null, "r", "", target(),
                Combinator.AND, 1, [condition(Stat.HITS, Comparator.GT, 1d)], [1L], [EMAIL]))

        then:
        rule.getOrgId() == null
        !rule.isOrganization()
    }

    // ── 不支持的形式 ─────────────────────────────────────────

    def "一期不支持环比告警：不存在环比相关字段"() {
        expect:
        !AlertRule.declaredFields*.name.any { it.toLowerCase().contains("mom")
                || it.toLowerCase().contains("compare") }
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
        !AlertRule.declaredFields*.name.any { it.toLowerCase().contains("history")
                || it.toLowerCase().contains("trigger") }
    }

    // ── 条件匹配语义 ─────────────────────────────────────────

    @Unroll
    def "条件 #comparator #threshold 对比值 #value → #expected"() {
        given:
        def c = condition(Stat.HITS, comparator, threshold)

        expect:
        c.matches(value) == expected

        where:
        comparator         | threshold | value | expected
        Comparator.GT      | 10d       | 11d   | true
        Comparator.GT      | 10d       | 10d   | false
        Comparator.GTE     | 10d       | 10d   | true
        Comparator.LT      | 10d       | 9d    | true
        Comparator.LT      | 10d       | 10d   | false
        Comparator.LTE     | 10d       | 10d   | true
        Comparator.EQ      | 10d       | 10d   | true
        Comparator.NEQ     | 10d       | 9d    | true
    }

    def "缺数不满足任何条件（未知点既不高于也不低于）"() {
        expect: "PRD 06 §6：未知点不满足『高于』或『低于』任何条件"
        !condition(Stat.HITS, Comparator.GT, 0d).matches(null)
        !condition(Stat.HITS, Comparator.LT, 100d).matches(null)
        !condition(Stat.HITS, Comparator.GTE, 0d).matches(null)
        !condition(Stat.HITS, Comparator.LTE, 100d).matches(null)
        !condition(Stat.HITS, Comparator.EQ, 0d).matches(null)
        !condition(Stat.HITS, Comparator.NEQ, 0d).matches(null)
    }

    def "零值可以满足条件（完整且确认无调用时次数类为 0）"() {
        expect: "PRD 06 §6：完整且确认无调用的次数类点可以是 0"
        condition(Stat.HITS, Comparator.LTE, 0d).matches(0.0d)
        condition(Stat.HITS, Comparator.EQ, 0d).matches(0.0d)
        !condition(Stat.HITS, Comparator.GT, 0d).matches(0.0d)
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

    def "原始指标规则引用条件中的统计项"() {
        when:
        def rule = AlertRule.draft(AlertScope.SERVICE, null, "r", "", target(), Combinator.AND, 1,
                [condition(Stat.FAILURE_RATE, Comparator.GT, 0.1d), condition(Stat.HITS, Comparator.GT, 10d)],
                [1L], [EMAIL])

        then:
        rule.referencedStats() as Set == [Stat.FAILURE_RATE, Stat.HITS] as Set
    }

    def "卡片结果规则引用卡片公式的统计项"() {
        when:
        def cardTarget = AlertTarget.cardResult(12L, "order", "TRANSACTION", "URL", "/a",
                [Stat.FAILURES, Stat.HITS])
        def rule = AlertRule.draft(AlertScope.ORGANIZATION, 7L, "r", "", cardTarget, Combinator.AND, 1,
                [condition(Stat.FAILURE_RATE, Comparator.GT, 0.1d)], [1L], [EMAIL])

        then:
        rule.referencedStats() as Set == [Stat.FAILURES, Stat.HITS] as Set
        rule.getTarget().isCardResult()
    }

    def "作用范围枚举完整覆盖两类告警"() {
        expect:
        AlertScope.values()*.name() as Set == ["SERVICE", "ORGANIZATION"] as Set
    }
}
