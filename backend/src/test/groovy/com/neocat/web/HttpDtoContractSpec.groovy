package com.neocat.web

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.paramnames.ParameterNamesModule

import com.neocat.query.api.http.convert.ReportConvert
import com.neocat.dashboard.api.http.convert.DashboardConvert
import com.neocat.dashboard.domain.card.*
import com.neocat.identity.api.http.convert.IdentityConvert
import com.neocat.identity.domain.auth.*
import com.neocat.identity.domain.account.*
import org.springframework.http.ResponseEntity
import spock.lang.Specification
import spock.lang.Unroll
import com.neocat.alert.api.http.AlertController
import com.neocat.alert.api.http.convert.AlertConvert
import com.neocat.alert.domain.engine.PreviewResult
import com.neocat.catalog.api.http.CatalogController
import com.neocat.catalog.api.http.convert.CatalogConvert
import com.neocat.dashboard.api.http.DashboardController
import com.neocat.dashboard.domain.access.CardInputSource
import com.neocat.identity.api.http.IdentityController
import com.neocat.identity.api.http.UserAdminController
import com.neocat.identity.domain.session.Session
import com.neocat.ingest.api.http.IngestController
import com.neocat.ingest.api.http.convert.IngestConvert
import com.neocat.organization.api.http.OrgAdminController
import com.neocat.organization.api.http.convert.OrgConvert
import com.neocat.organization.domain.tree.OrgNode
import com.neocat.platform.api.http.PlatformController
import com.neocat.platform.api.http.convert.PlatformConvert
import com.neocat.platform.domain.profile.PlatformProfile
import com.neocat.query.api.http.MetricCountController
import com.neocat.query.api.http.ReportController
import com.neocat.trace.api.http.TraceController
import com.neocat.trace.api.http.convert.TraceConvert
import com.neocat.trace.domain.tree.NodeAvailability
import com.neocat.trace.domain.tree.TraceTreeNode
import java.time.Instant
import org.mapstruct.factory.Mappers
import org.springframework.core.annotation.AnnotatedElementUtils
import org.springframework.web.bind.annotation.RequestMapping
import com.neocat.alert.api.http.dto.AlertDraft
import com.neocat.alert.domain.engine.PointEvaluation
import com.neocat.dashboard.api.http.dto.CardDraft
import com.neocat.dashboard.api.http.dto.DashboardDraft
import com.neocat.identity.api.http.dto.ChangePasswordRequest
import com.neocat.identity.api.http.dto.LoginRequest
import com.neocat.identity.api.http.dto.PasswordDraft
import com.neocat.identity.api.http.dto.RoleDraft
import com.neocat.identity.api.http.dto.UserDraft
import com.neocat.ingest.domain.receive.QueueStats
import com.neocat.organization.api.http.dto.MemberDraft
import com.neocat.organization.api.http.dto.OrgDraft
import com.neocat.platform.api.http.dto.ChannelsRequest
import com.neocat.platform.api.http.dto.InitRequest
import com.neocat.platform.api.http.dto.SlowThresholdsRequest
import com.neocat.query.api.http.dto.Series
import com.neocat.trace.domain.tree.AssemblyResult

/** 实际执行 Jackson 绑定/输出，覆盖 Lombok 构造器及保留 null 的契约。 */
class HttpDtoContractSpec extends Specification {
    ObjectMapper json = new ObjectMapper().registerModule(new ParameterNamesModule())

    @Unroll
    def "请求 DTO #type.simpleName 由真实 JSON 绑定，字段不静默为空"() {
        when:
        def dto = json.readValue(json.writeValueAsString(input), type)

        then:
        input.each { key, value -> assert dto."$key" == value }

        where:
        type | input
        LoginRequest | [username:'alice', password:'password']
        ChangePasswordRequest | [oldPassword:'old-password', newPassword:'new-password']
        UserDraft | [username:'bob', password:'password']
        PasswordDraft | [password:'reset-password']
        RoleDraft | [role:'ADMIN']
        OrgDraft | [name:'研发', parentId:7L]
        MemberDraft | [userId:9L]
        InitRequest | [timezone:'Asia/Shanghai', adminUsername:'root', adminPassword:'password']
        SlowThresholdsRequest | [url:1000, sql:100, call:1000, cache:50]
        ChannelsRequest | [email:true, dingtalk:false, feishu:true]
        DashboardDraft | [orgId:7L, name:'大盘']
    }

    def "告警和卡片嵌套请求字段实际绑定"() {
        when:
        def alert = json.readValue('''{"scope":"SERVICE","name":"rule","windowPoints":2,
            "target":{"kind":"RAW_METRIC","service":"s","reportKind":"TRANSACTION","type":"URL","name":"n"},
            "conditions":[{"stat":"hits","comparator":"GT","threshold":10}],"recipients":[7],"channels":["EMAIL"]}''', AlertDraft)
        def card = json.readValue('''{"service":"s","targetKind":"TRANSACTION","targetType":"URL","targetName":"n",
            "formula":"hits","timeRange":"RECENT_24H","thresholdLines":[{"direction":"ABOVE","value":10}]}''', CardDraft)

        then:
        alert.target.service == 's'
        alert.target.name == 'n'
        alert.conditions[0].threshold == 10d
        alert.recipients == [7L]
        card.service == 's'
        card.thresholdLines[0].direction == 'ABOVE'
        card.thresholdLines[0].value == 10d
    }

    def "报表 DTO 不增加包装、不丢弃显式 null、字段完全等同原读模型"() {
        given:
        def model = [service:'s', kind:'EVENT', type:null, name:null, stat:'HITS', unit:'COUNT',
                     bucketSeconds:60, range:[from:1L,to:2L], mom:null,
                     points:[[bucketStart:1L,bucketEnd:2L,value:null,quality:'NO_DATA',coveredSeconds:0L,realtime:false,partial:false]]]

        when:
        def dto = new ReportConvert(json).response(model, Series)

        then:
        json.readTree(json.writeValueAsString(dto)) == json.readTree(json.writeValueAsString(model))
    }

    def "卡片序列保留缺口与除零分开表达、阈值字段仍为 direction 和 value"() {
        given:
        def card = new Card(1L, 7L, 's', 'TRANSACTION', 'URL', 'n', null, [], 'hits', 'RECENT_24H', 0,
                [new ThresholdLine(ThresholdDirection.ABOVE, 10)])
        def model = new CardSeriesService(Stub(CardInputSource))
                .describe(card, [], 'COUNT')

        expect:
        json.readTree(json.writeValueAsString(Mappers.getMapper(DashboardConvert).series(model))) == json.readTree(json.writeValueAsString(model))
        model.isUndefined == []
        !model.containsKey('undefined')
        json.readTree(json.writeValueAsString(Mappers.getMapper(DashboardConvert).series(model))).has('isUndefined')
        !json.readTree(json.writeValueAsString(Mappers.getMapper(DashboardConvert).series(model))).has('undefined')
    }

    def "卡片序列 isUndefined 数组只收除零，缺数与真实零值不混淆"() {
        given:
        def card = Card.withoutThresholds(1, 7, 's', 'TRANSACTION', 'URL', 'n', null, [], 'failures / hits', 'RECENT_24H', 0)
        def points = [new CardPoint(1, 2, null, CardPointOutcome.GAP, ['HITS']),
                      new CardPoint(3, 4, null, CardPointOutcome.DIVIDE_BY_ZERO, []),
                      new CardPoint(5, 6, 0d, CardPointOutcome.OK, [])]
        def model = new CardSeriesService(Stub(CardInputSource)).describe(card, points, 'RATE')

        when:
        def response = json.readTree(json.writeValueAsString(Mappers.getMapper(DashboardConvert).series(model)))

        then:
        model.isUndefined == [[bucketStart:3L, reason:'DIVIDE_BY_ZERO']]
        !model.containsKey('undefined')
        response == json.readTree(json.writeValueAsString(model))
        response.get('isUndefined').isArray()
        response.get('isUndefined').size() == 1
        !response.has('undefined')
        response.get('gaps') == json.readTree('[{"bucketStart":1,"missingInputs":["HITS"]}]')
        response.get('points').get(0).get('value').isNull()
        response.get('points').get(1).get('value').isNull()
        response.get('points').get(2).get('value').doubleValue() == 0d
    }

    def "Controller 每个映射方法统一返回 ResponseEntity 且无 Map 或领域响应"() {
        given:
        def controllers = [IdentityController, UserAdminController,
            OrgAdminController, PlatformController,
            CatalogController, TraceController,
            DashboardController, AlertController,
            ReportController, MetricCountController,
            IngestController]

        expect:
        controllers.each { controller ->
            controller.declaredMethods.findAll { method -> method.annotations.any {
                AnnotatedElementUtils.hasAnnotation(it.annotationType(), RequestMapping)
            } }.each { method ->
                assert method.returnType == ResponseEntity
                assert !method.genericReturnType.typeName.contains('java.util.Map')
                assert !method.genericReturnType.typeName.contains('.domain.')
            }
        }
    }

    def "身份响应不泄漏密码和 Session，服务列表落点保持原省略规则"() {
        given:
        def account = new Account(7, 'alice', 'secret-hash', Role.USER, AccountStatus.ENABLED, true, Instant.EPOCH)
        def result = new LoginResult(new Session('secret-token', 7, Instant.EPOCH), account, true)

        expect:
        json.readTree(json.writeValueAsString(IdentityConvert.login(result, LoginTarget.SERVICE_LIST))) ==
                json.readTree('''{"user":{"id":7,"username":"alice","role":"USER"},"mustChangePassword":true,
                    "entry":{"type":"SERVICE_LIST"}}''')
        json.readTree(json.writeValueAsString(IdentityConvert.login(result, new LoginTarget('s')))) ==
                json.readTree('''{"user":{"id":7,"username":"alice","role":"USER"},"mustChangePassword":true,
                    "entry":{"type":"SERVICE_TRANSACTION","service":"s","kind":"TRANSACTION"}}''')
        json.readTree(json.writeValueAsString(IdentityConvert.user(account))) ==
                json.readTree('''{"id":7,"username":"alice","role":"USER","status":"ENABLED","mustChangePassword":true}''')
    }

    def "组织、平台、目录与上报观测响应保持固定字段而不暴露领域类型"() {
        expect:
        json.readTree(json.writeValueAsString(OrgConvert.node(
                new OrgNode(7, '研发', null), true, 2))) ==
                json.readTree('''{"id":7,"name":"研发","parentId":null,"leaf":true,"memberCount":2}''')
        json.readTree(json.writeValueAsString(PlatformConvert.profile(
                PlatformProfile.notInitialized(), []))) ==
                json.readTree('''{"timezone":"Asia/Shanghai","initialized":false,
                    "slow":{"url":1000,"sql":100,"call":1000,"cache":50},
                    "channels":{"email":false,"dingtalk":false,"feishu":false}}''')
        json.readTree(json.writeValueAsString(CatalogConvert.service('s', ['i']))) ==
                json.readTree('''{"name":"s","instances":["i"]}''')
        json.readTree(json.writeValueAsString(IngestConvert.stats(
                new QueueStats(2, 10, 3, 0.2)))) ==
                json.readTree('''{"size":2,"capacity":10,"droppedTotal":3,"watermark":0.2}''')
    }

    def "告警预览缺数字段与卡片缺口、除零保持原结构"() {
        given:
        def preview = PreviewResult.insufficient([
                new PointEvaluation(1, false, false, 'HITS')])
        def card = Card.withoutThresholds(1, 7, 's', 'TRANSACTION', 'URL', 'n', null, [], 'hits', 'RECENT_24H', 0)
        def model = [cardId:1L, formula:'hits', unit:'COUNT', thresholdLines:[],
                     points:[[bucketStart:1L,bucketEnd:2L,value:null,outcome:'GAP']],
                     gaps:[[bucketStart:1L,missingInputs:['HITS']]], isUndefined:[[bucketStart:3L,reason:'DIVIDE_BY_ZERO']]]

        expect:
        json.readTree(json.writeValueAsString(Mappers
                .getMapper(AlertConvert).preview(preview))) ==
                json.readTree('''{"result":"INSUFFICIENT_DATA","points":[{"minute":1,"known":false,"satisfied":false,"missingStat":"HITS"}]}''')
        json.readTree(json.writeValueAsString(Mappers.getMapper(DashboardConvert).series(model))) == json.readTree(json.writeValueAsString(model))
        json.readTree(json.writeValueAsString(Mappers.getMapper(DashboardConvert).card(card))).fieldNames().toList().toSet() ==
                ['id','dashboardId','service','targetKind','targetType','targetName','metricLabels','formula','timeRange','thresholdLines','unit'] as Set
    }

    def "Trace 递归 DTO 保留缺失与过期计数、时间数值和 null 原因"() {
        given:
        def root = new TraceTreeNode('m', 's', 'i', 123,
                NodeAvailability.PRESENT, null)
        def assembly = new AssemblyResult(root, false, false)

        expect:
        json.readTree(json.writeValueAsString(TraceConvert.response('m', assembly))) ==
                json.readTree('''{"messageId":"m","expired":false,"missingNodes":0,"expiredNodes":0,"children":[
                    {"messageId":"m","service":"s","instance":"i","availability":"PRESENT","reason":null,
                     "treeTimestamp":123,"spans":[],"children":[]}]}''')
    }
}
