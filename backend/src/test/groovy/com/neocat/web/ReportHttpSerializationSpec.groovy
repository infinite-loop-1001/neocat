package com.neocat.web

import com.fasterxml.jackson.databind.ObjectMapper
import com.neocat.analysis.domain.bucket.*
import com.neocat.common.time.bucket.TimeBucketResolver
import com.neocat.query.api.http.*
import com.neocat.query.api.http.convert.ReportConvert
import com.neocat.query.domain.report.ReportTableService
import com.neocat.query.domain.series.*
import com.neocat.query.domain.stat.StatCalculator
import com.neocat.query.infra.port.*
import com.neocat.query.infra.service.*
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders
import spock.lang.Specification
import spock.lang.Unroll
import java.time.*

/** 真正通过 Spring MVC 输出 JSON，对比重构前保留的读模型，而不是 DTO 的反射字段。 */
class ReportHttpSerializationSpec extends Specification {
    def json = new ObjectMapper()

    def clock = Clock.fixed(Instant.parse('2026-10-03T08:30:00Z'), ZoneOffset.UTC)

    def data = Stub(ReportDataPort) {
        typesOf(*_) >> ['URL']
        namesOf(*_) >> ['name']
        instancesWithData(*_) >> ['instance']
        rows(*_) >> { args ->
            def row = new AggregatedRow(SeriesKey.of('s', SeriesKind.valueOf(args[0]), 'URL', 'name', 'instance'),
                    args[4], AggregationLevel.MINUTE, 60)
            [row]
        }
    }

    def query = new ReportQueryService(data, new com.neocat.common.time.bucket.DefaultTimeBucketResolver(), new ReportTableService(),
            new StatCalculator(), new QualityResolver(), new MomAligner(), Stub(SamplePort) {
                samples(*_) >> [new com.neocat.trace.domain.sample.Sample('m', 1, 2, '0', 'summary', true)]
            }, { ZoneOffset.UTC }, clock)

    @Unroll
    def "报表 HTTP #route 保留读模型所有字段、null、空数组和数值类型"() {
        given:
        def controller = new ReportController(query, new ReportConvert(json))
        def mvc = MockMvcBuilders.standaloneSetup(controller)
                .setMessageConverters(new MappingJackson2HttpMessageConverter(json)).build()
        def request = MockMvcRequestBuilders.get('/api/reports/' + route)
        params.each { key, value -> request.param(key, value) }
        def expected = query."$method"(*arguments)

        when:
        def response = mvc.perform(request).andReturn().response

        then:
        response.status == 200
        json.readTree(response.contentAsString) == json.readTree(json.writeValueAsString(expected))

        where:
        route | params | method | arguments
        'transaction/types' | [service:'s'] | 'transactionTypes' | ['s','RECENT_1H']
        'transaction/names' | [service:'s',type:'URL'] | 'transactionNames' | ['s','URL','RECENT_1H']
        'event/types' | [service:'s'] | 'eventTypes' | ['s','RECENT_1H']
        'event/names' | [service:'s',type:'URL'] | 'eventNames' | ['s','URL','RECENT_1H']
        'problem/categories' | [service:'s'] | 'problemCategories' | ['s','RECENT_1H']
        'problem/names' | [service:'s',category:'EXCEPTION'] | 'problemNames' | ['s','EXCEPTION','RECENT_1H']
        'series' | [service:'s',kind:'TRANSACTION',mom:'DAY'] | 'series' | ['s','TRANSACTION',null,null,'HITS','RECENT_1H',null,'DAY',null]
        'heartbeat/metrics' | [:] | 'heartbeatMetrics' | []
        'heartbeat/instances' | [service:'s'] | 'heartbeatInstances' | ['s','heap-used','RECENT_1H']
        'heartbeat/series' | [service:'s'] | 'heartbeatSeries' | ['s','heap-used','RECENT_1H',null]
        'metric/list' | [service:'s'] | 'metricList' | ['s',null]
        'dependency/upstream' | [service:'s'] | 'upstream' | ['s','RECENT_1H']
        'dependency/downstream' | [service:'s'] | 'downstream' | ['s','RECENT_1H']
        'samples' | [service:'s'] | 'samples' | ['s',null,null,null,'RECENT_1H',null]
    }

    @Unroll
    def "Metric HTTP #route 保留标签数组和 Long count 缺数"() {
        given:
        def metricData = Stub(ReportDataPort) {
            typesOf(*_) >> ['metric']
            metricSourceRows(*_) >> []
        }
        def metadata = Stub(MetricMetadataPort) { entries(*_) >> [] }
        def metricQuery = new MetricCountQueryService(metricData, metadata,
                new com.neocat.common.time.bucket.DefaultTimeBucketResolver(), { ZoneOffset.UTC }, clock, json)
        def mvc = MockMvcBuilders.standaloneSetup(new MetricCountController(metricQuery, new ReportConvert(json)))
                .setMessageConverters(new MappingJackson2HttpMessageConverter(json)).build()
        def expected = metricQuery."$method"(*arguments)

        when:
        def response = mvc.perform(MockMvcRequestBuilders.get('/api/reports/metric/' + route)
                .param('service', 's').param('metric', 'metric')).andReturn().response

        then:
        response.status == 200
        json.readTree(response.contentAsString) == json.readTree(json.writeValueAsString(expected))

        where:
        route | method | arguments
        'metrics' | 'metrics' | ['s','RECENT_1H']
        'labels' | 'labels' | ['s','metric','RECENT_1H']
        'count' | 'count' | ['s','metric','RECENT_1H',null]
    }
}
