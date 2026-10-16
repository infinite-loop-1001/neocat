package com.neocat.alert.infra.jdbc

import com.neocat.alert.domain.entity.AlertRule
import com.neocat.alert.domain.entity.vo.rule.AlertChannel
import com.neocat.alert.domain.entity.vo.cursor.AlertEvalCursor
import com.neocat.alert.domain.entity.vo.rule.AlertScope
import com.neocat.alert.domain.entity.vo.rule.AlertTarget
import com.neocat.alert.domain.entity.vo.rule.AlertTargetKind
import com.neocat.alert.domain.entity.vo.rule.Combinator
import com.neocat.alert.domain.entity.vo.rule.Comparator
import com.neocat.alert.domain.entity.vo.rule.Condition
import com.neocat.alert.infra.jdbc.row.AlertRuleRow
import com.neocat.alert.infra.jdbc.row.AlertEvalCursorRow
import com.neocat.query.domain.stat.Stat
import spock.lang.Specification

import java.sql.Timestamp
import java.time.Instant

class AlertRuleRepositoryAdapterSpec extends Specification {
    def 'persisted target retains its report kind, description and metric label selector'() {
        given:
        def mapper = Mock(AlertMapper)
        def adapter = new AlertRuleRepositoryAdapter(mapper)
        def target = new AlertTarget(AlertTargetKind.RAW_METRIC, 0L, 'order',
                'METRIC', 'order.amount', '', 'city=上海;', [])
        def rule = AlertRule.draft(AlertScope.SERVICE, null, 'threshold', 'business note',
                target, Combinator.AND, 1,
                [new Condition(Stat.HITS, Comparator.GT, 1d)], ['keith.ji'], [AlertChannel.EMAIL])

        when:
        def stored = adapter.save(rule)

        then:
        1 * mapper.insertRule({ row -> row.targetKind == 'RAW_METRIC' && row.reportKind == 'METRIC'
            && row.targetMetricLabels == 'city=上海;' && row.description == 'business note'
            && row.targetStat == 'HITS' && row.channels == 'EMAIL' && row.targetCardId == null }) >>
                { args -> args[0].id = 10L; 1 }
        1 * mapper.insertCondition(10L, 'HITS', 'GT', 1d)
        1 * mapper.insertRecipient(10L, 'keith.ji', 'EMAIL')
        stored.getId() == 10L
    }

    def 'loading the rule uses stored report type and keeps its description'() {
        given:
        def mapper = Mock(AlertMapper)
        def adapter = new AlertRuleRepositoryAdapter(mapper)
        def row = new AlertRuleRow()
        row.id = 10L
        row.scope = 'SERVICE'
        row.name = 'threshold'
        row.description = 'business note'
        row.targetKind = 'RAW_METRIC'
        row.reportKind = 'METRIC'
        row.targetService = 'order'
        row.targetType = 'order.amount'
        row.targetName = ''
        row.targetMetricLabels = 'city=上海;'
        row.combinator = 'AND'
        row.channels = 'EMAIL'
        row.windowPoints = 1

        when:
        def rule = adapter.findById(10L)

        then:
        1 * mapper.selectRule(10L) >> row
        1 * mapper.selectConditions(10L) >> []
        1 * mapper.selectRecipients(10L) >> []
        rule.getTarget().getReportKind() == 'METRIC'
        rule.getTarget().getMetricLabels() == 'city=上海;'
        rule.getDescription() == 'business note'
    }

    def 'card result formula dependencies survive a database round trip'() {
        given:
        def mapper = Mock(AlertMapper)
        def adapter = new AlertRuleRepositoryAdapter(mapper)
        def row = new AlertRuleRow()
        row.id = 42L
        row.scope = 'ORGANIZATION'
        row.orgId = 7L
        row.targetKind = 'CARD_RESULT'
        row.targetCardId = 12L
        row.reportKind = 'TRANSACTION'
        row.targetService = 'order'
        row.formulaStats = 'FAILURES,HITS'
        row.combinator = 'AND'
        row.channels = 'EMAIL'

        when:
        def loaded = adapter.findById(42L)

        then:
        1 * mapper.selectRule(42L) >> row
        1 * mapper.selectConditions(42L) >> []
        1 * mapper.selectRecipients(42L) >> []
        loaded.getTarget().getFormulaStats() == [Stat.FAILURES, Stat.HITS]
        loaded.getTarget().getCardId() == 12L
    }

    def 'removing the last recipient does not erase selected notification channels'() {
        given:
        def mapper = Mock(AlertMapper)
        def adapter = new AlertRuleRepositoryAdapter(mapper)
        def row = new AlertRuleRow()
        row.id = 5L
        row.scope = 'SERVICE'
        row.name = 'threshold'
        row.targetKind = 'RAW_METRIC'
        row.reportKind = 'TRANSACTION'
        row.targetService = 'order'
        row.combinator = 'AND'
        row.windowPoints = 1
        row.channels = 'EMAIL,DINGTALK'

        when:
        def restored = adapter.findById(5L)

        then:
        1 * mapper.selectRule(5L) >> row
        1 * mapper.selectConditions(5L) >> []
        1 * mapper.selectRecipients(5L) >> []
        restored.getRecipients().isEmpty()
        restored.getChannels() == [AlertChannel.EMAIL, AlertChannel.DINGTALK]
    }

    def 'rule with no recipients still stores selected channels in its own row'() {
        given:
        def mapper = Mock(AlertMapper)
        def adapter = new AlertRuleRepositoryAdapter(mapper)
        def draft = AlertRule.draft(AlertScope.SERVICE, null, 'threshold', '',
                AlertTarget.rawMetric('order', 'TRANSACTION', 'URL', '/a'), Combinator.AND, 1,
                [new Condition(Stat.HITS, Comparator.GT, 1d)], [],
                [AlertChannel.EMAIL, AlertChannel.FEISHU])

        when:
        adapter.save(draft)

        then:
        1 * mapper.insertRule({ it.channels == 'EMAIL,FEISHU' }) >> { args -> args[0].id = 20L; 1 }
        1 * mapper.insertCondition(20L, 'HITS', 'GT', 1d)
        0 * mapper.insertRecipient(_, _, _)
    }

    def 'deleting a rule explicitly cascades conditions, recipients and the evaluation cursor first'() {
        given:
        def mapper = Mock(AlertMapper)
        def adapter = new AlertRuleRepositoryAdapter(mapper)
        def row = new AlertRuleRow()
        row.id = 9L
        row.scope = 'ORGANIZATION'
        row.orgId = 7L

        when:
        adapter.delete(9L)

        then:
        1 * mapper.selectRule(9L) >> row
        1 * mapper.deleteConditions(9L)
        1 * mapper.deleteRecipients(9L)
        1 * mapper.deleteAlertEvalCursor(9L)
        1 * mapper.deleteRule(9L)
    }

    def 'clearing an evaluation cursor drops every window point of the rule'() {
        given:
        def mapper = Mock(AlertMapper)
        def adapter = new AlertRuleRepositoryAdapter(mapper)

        when:
        adapter.clearAlertEvalCursor(9L)

        then:
        1 * mapper.deleteAlertEvalCursor(9L)
    }

    def 'saving a cursor replaces the previous row and keeps an unjudged baseline empty'() {
        given:
        def mapper = Mock(AlertMapper)
        def adapter = new AlertRuleRepositoryAdapter(mapper)
        def baseline = Instant.parse('2026-10-17T01:00:00Z')

        when:
        adapter.saveAlertEvalCursor(AlertEvalCursor.empty(9L, baseline.toEpochMilli()))

        then:
        1 * mapper.deleteAlertEvalCursor(9L)
        1 * mapper.insertAlertEvalCursor({
            it.ruleId == 9L && it.baselineAt == Timestamp.from(baseline) && it.lastEvaluatedMinute == null
        })
    }

    def 'loading a cursor restores the baseline and the last judged minute'() {
        given:
        def mapper = Mock(AlertMapper)
        def adapter = new AlertRuleRepositoryAdapter(mapper)
        def baseline = Instant.parse('2026-10-17T01:00:00Z')
        def judged = baseline.plusSeconds(60)
        def row = new AlertEvalCursorRow()
        row.id = 3L
        row.ruleId = 9L
        row.baselineAt = Timestamp.from(baseline)
        row.lastEvaluatedMinute = Timestamp.from(judged)

        when:
        def cursor = adapter.findAlertEvalCursor(9L)

        then:
        1 * mapper.selectAlertEvalCursor(9L) >> row
        cursor.id == 3L
        cursor.ruleId == 9L
        cursor.baselineAt == baseline.toEpochMilli()
        cursor.lastEvaluatedMinute == judged.toEpochMilli()
    }
}
