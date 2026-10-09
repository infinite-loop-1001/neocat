package com.neocat.alert.api.http

import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.paramnames.ParameterNamesModule
import com.neocat.alert.api.http.convert.AlertConvert
import com.neocat.alert.api.http.dto.rule.AlertDraft
import com.neocat.alert.domain.engine.MinutePointSource
import com.neocat.alert.domain.engine.NotificationDispatcher
import com.neocat.alert.domain.engine.PreviewService
import com.neocat.alert.domain.recipient.RecipientGateway
import com.neocat.alert.domain.recipient.RecipientService
import com.neocat.alert.domain.rule.AlertLifecycleService
import com.neocat.alert.domain.rule.AlertRuleRepository
import com.neocat.alert.domain.rule.AlertRuleService
import com.neocat.alert.domain.rule.AlertScope
import com.neocat.common.error.exception.ValidationException
import com.neocat.common.http.error.ApiExceptionHandler
import com.neocat.dashboard.api.http.convert.DashboardConvert
import com.neocat.dashboard.domain.access.CardInputSource
import com.neocat.dashboard.domain.card.*
import com.neocat.query.api.http.dto.report.Point
import com.neocat.query.domain.stat.Stat
import org.mapstruct.factory.Mappers
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import spock.lang.Specification

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status

class DecimalJsonSpec extends Specification {
    def json = new ObjectMapper().registerModule(new ParameterNamesModule())
    def convert = Mappers.getMapper(AlertConvert)

    def '真实 HTTP 绑定保留大阈值，缺失与不可存储阈值返回 400'() {
        given:
        def points = Stub(MinutePointSource) { values(_, _, _) >> [(Stat.HITS): 99999999999999.999999] }
        def controller = new AlertController(Stub(AlertRuleRepository), Stub(AlertRuleService), Stub(AlertLifecycleService),
                new PreviewService(points), Stub(RecipientService), Stub(NotificationDispatcher), Stub(RecipientGateway), convert)
        def mvc = MockMvcBuilders.standaloneSetup(controller).setControllerAdvice(new ApiExceptionHandler())
                .setMessageConverters(new MappingJackson2HttpMessageConverter(json)).build()
        def draft = { field -> '{"scope":"SERVICE","windowPoints":1,"conditions":[{"stat":"HITS","comparator":"EQ"' + field + '}]}' }
        expect:
        mvc.perform(post('/api/alerts/preview').contentType('application/json')
                .content(draft(',"threshold":99999999999999.999999')))
                .andExpect(status().isOk()).andExpect(jsonPath('$.result').value('TRIGGER'))
        ['', ',"threshold":null', ',"threshold":0.0000001', ',"threshold":100000000000000'].each { field ->
            mvc.perform(post('/api/alerts/preview').contentType('application/json').content(draft(field)))
                    .andExpect(status().isBadRequest()).andExpect(jsonPath('$.code').value(10002))
        }
    }

    def '生成的卡片 Convert 正确映射枚举列表、阈值与空集合'() {
        given:
        def dashboardConvert = Mappers.getMapper(DashboardConvert)
        def target = new AlertableTarget(AlertableTargetKind.CARD_RESULT, 1L, 'order',
                'TRANSACTION', 'URL', '/a', null, [Stat.HITS, Stat.FAILURE_RATE])
        def card = new Card(1L, 1L, 'order', 'TRANSACTION', 'URL', '/a', null, [],
                'hits', 'RECENT_1H', 0, [new ThresholdLine(ThresholdDirection.ABOVE, 1.500000)])
        expect:
        dashboardConvert.target(target).getKind() == 'CARD_RESULT'
        dashboardConvert.target(target).getStats() == ['HITS', 'FAILURE_RATE']
        dashboardConvert.card(card).getThresholdLines()[0].getDirection() == 'ABOVE'
        dashboardConvert.card(card).getThresholdLines()[0].getValue().toPlainString() == '1.500000'
        dashboardConvert.thresholds(null) == []
    }

    def '阈值从 JSON 原始十进制数字绑定并经 MapStruct 往返不损失精度'() {
        given:
        String body = '''{"scope":"SERVICE","windowPoints":1,
            "conditions":[{"stat":"HITS","comparator":"EQ","threshold":99999999999999.999999}]}'''
        def draft = json.readValue(body, AlertDraft)
        def rule = convert.rule(draft, AlertScope.SERVICE)
        def output = json.writeValueAsString(convert.response(rule))
        def preciseJson = json.copy().enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS)

        expect:
        draft.getConditions()[0].getThreshold().toPlainString() == '99999999999999.999999'
        rule.getConditions()[0].getThreshold().toPlainString() == '99999999999999.999999'
        output.contains('"threshold":99999999999999.999999')
        preciseJson.readTree(output).get('conditions')[0].get('threshold').isNumber()
    }

    def '请求中省略或明确 null 的阈值在转换边界拒绝而不是默认零'() {
        given:
        def draft = json.readValue('''{"conditions":[{"stat":"HITS","comparator":"EQ"''' + field + '}] }',
                AlertDraft)
        when:
        convert.rule(draft, AlertScope.SERVICE)
        then:
        thrown(ValidationException)
        where:
        field << ['', ',"threshold":null']
    }

    def '报表 DTO 十进制结果序列化仍是数字并保留缺数 null'() {
        given:
        def point = new Point()
        point.setValue(new BigDecimal('9007199254740993.123456'))
        String body = json.writeValueAsString(point)
        expect:
        body.contains('"value":9007199254740993.123456')
        json.readValue(body, Point).getValue().toPlainString() == '9007199254740993.123456'
        json.writeValueAsString(new Point()).contains('"value":null')
    }

    def '卡片 JSON 保持数字、gaps 和 isUndefined 数组契约'() {
        given:
        def card = Card.withoutThresholds(1L, 1L, 'order', 'TRANSACTION', 'URL', '/a', null,
                [], 'hits', 'RECENT_1H', 0)
        def series = new CardSeriesService(Stub(CardInputSource))
        def points = [new CardPoint(0L, 60L, 1.500000, CardPointOutcome.OK, []),
                      new CardPoint(60L, 120L, null, CardPointOutcome.GAP, ['hits']),
                      new CardPoint(120L, 180L, null, CardPointOutcome.DIVIDE_BY_ZERO, [])]
        def response = Mappers.getMapper(DashboardConvert).series(series.describe(card, points, 'COUNT'))
        def tree = json.readTree(json.writeValueAsString(response))
        expect:
        response.getPoints()[0].getValue() instanceof BigDecimal
        tree.get('points')[0].get('value').isNumber()
        tree.get('points')[1].get('value').isNull()
        tree.get('gaps').size() == 1
        tree.get('isUndefined').size() == 1
        tree.get('isUndefined')[0].get('reason').asText() == 'DIVIDE_BY_ZERO'
        json.readTree(json.writeValueAsString(Mappers.getMapper(DashboardConvert).series(series.describe(card, [points[0]], 'COUNT'))))
                .get('isUndefined').size() == 0
    }
}
