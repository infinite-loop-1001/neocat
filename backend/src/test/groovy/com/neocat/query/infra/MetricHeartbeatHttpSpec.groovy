package com.neocat.query.infra

import com.neocat.query.infra.datasource.ClickHouseReportDataPort
import com.neocat.query.infra.datasource.ClickHouseReportQuery
import com.neocat.query.infra.datasource.HourlyReportDataPort
import com.neocat.query.infra.datasource.ReportDataPortRouter
import com.neocat.query.infra.port.MetricMetadataPort
import com.neocat.query.infra.port.SamplePort

import spock.lang.Specification
import com.fasterxml.jackson.databind.ObjectMapper
import com.neocat.analysis.domain.analyzer.*
import com.neocat.analysis.domain.bucket.*
import com.neocat.analysis.domain.dependency.*
import com.neocat.analysis.domain.metric.*
import com.neocat.analysis.domain.schedule.*
import com.neocat.analysis.infra.*
import com.neocat.analysis.infra.adapter.*
import com.neocat.analysis.infra.jdbc.*
import com.neocat.analysis.infra.job.*
import com.neocat.analysis.infra.store.*
import com.neocat.common.time.bucket.*
import com.neocat.common.time.clock.*
import com.neocat.common.time.range.*
import com.neocat.query.api.http.*
import com.neocat.query.domain.metric.*
import com.neocat.query.domain.report.*
import com.neocat.query.domain.series.*
import com.neocat.query.domain.stat.*
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import java.time.*
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*

class MetricHeartbeatHttpSpec extends Specification {
    def time = Instant.parse('2026-10-02T10:00:00Z')
    def json = new ObjectMapper()

    def "真实 Controller 默认全量、条件聚合与单指标候选，mock 关闭契约不变"() {
        given:
        def memory = new InMemoryHourlyReportStore()
        def labels = new InMemoryMetricLabelMetadata()
        com.neocat.common.config.MetricConfig.TOP_N = 2
        def analyzer = new MetricAnalyzer(memory, new InMemoryMetricHourRank(), labels)
        analyzer.analyze(AnalysisFixtures.metricTree('order','one',time.toEpochMilli(), 'order.amount',99d,[channel:'app',city:'上海']))
        analyzer.analyze(AnalysisFixtures.metricTree('order','one',time.toEpochMilli(), 'order.amount',10d,[channel:'web',city:'北京']))
        def buckets = new DefaultTimeBucketResolver()
        def data = new HourlyReportDataPort(memory,buckets,{ZoneOffset.UTC},labels)
        def metadata = Stub(MetricMetadataPort) { entries(_,_,_,_) >> { a -> labels.entries(a[2],a[3]) } }
        def controller = new MetricCountController(data,metadata,buckets,{ZoneOffset.UTC},Clock.fixed(time.plusSeconds(30),ZoneOffset.UTC),json)
        def mvc = MockMvcBuilders.standaloneSetup(controller).setControllerAdvice(new com.neocat.common.http.error.ApiExceptionHandler()).build()
        def range = 'HOUR:' + time.toEpochMilli()
        expect:
        mvc.perform(get('/api/reports/metric/metrics').param('service','order').param('range',range))
            .andExpect(status().isOk()).andExpect(jsonPath('$[0].name').value('order.amount'))
        mvc.perform(get('/api/reports/metric/count').param('service','order').param('metric','order.amount').param('range',range))
            .andExpect(status().isOk()).andExpect(jsonPath('$.points[0].value').value(2))
            .andExpect(jsonPath('$.points[1].quality').value('NO_DATA'))
        mvc.perform(get('/api/reports/metric/count').param('service','order').param('metric','order.amount').param('range',range)
            .param('filters','{"channel":["app"]}'))
            .andExpect(status().isOk()).andExpect(jsonPath('$.points[0].value').value(1))
        controller.labels('order','order.amount',range).body.find { it.key == 'channel' }.values == ['app','web']
        mvc.perform(get('/api/reports/metric/count').param('service','order').param('metric','order.amount').param('range',range).param('filters','[]'))
            .andExpect(status().isBadRequest()).andExpect(jsonPath('$.code').value(10002))
        mvc.perform(get('/api/reports/metric/count').param('service','order').param('metric','missing').param('range',range))
            .andExpect(status().isNotFound()).andExpect(jsonPath('$.code').value(10003))
    }

    def "Heartbeat 真实 Controller 用最后值按实例独立，粗桶不求和"() {
        given:
        def memory = new InMemoryHourlyReportStore()
        def current = time
        memory.addValue(SeriesKey.of('order',SeriesKind.HEARTBEAT,'jvm','gc-count','one'),current,100d)
        memory.addValue(SeriesKey.of('order',SeriesKind.HEARTBEAT,'jvm','gc-count','one'),current.plusMillis(1),0d)
        memory.addValue(SeriesKey.of('order',SeriesKind.HEARTBEAT,'jvm','gc-count','two'),current,40d)
        def buckets = new DefaultTimeBucketResolver()
        def data = new HourlyReportDataPort(memory,buckets,{ZoneOffset.UTC})
        def controller = new ReportController(data,buckets,new ReportTableService(),new StatCalculator(),
            new QualityResolver(),new MomAligner(),Stub(SamplePort),{ZoneOffset.UTC},Clock.fixed(current.plusSeconds(30),ZoneOffset.UTC))
        when:
        def result = controller.heartbeatSeries('order','gc-count','HOUR:' + current.toEpochMilli(),null).body
        then:
        controller.heartbeatMetrics().body.size() == 20
        result.series.size() == 2
        result.series.find { it.instance == 'one' }.points[0].value == 0d
        result.series.find { it.instance == 'two' }.points[0].value == 40d
        result.series.every { it.points[1].value == null }
        result.mom == null
        def mvc = MockMvcBuilders.standaloneSetup(controller).setControllerAdvice(new com.neocat.common.http.error.ApiExceptionHandler()).build()
        mvc.perform(get('/api/reports/heartbeat/series').param('service','order').param('metric','gc-count').param('range','HOUR:' + current.toEpochMilli()))
            .andExpect(status().isOk()).andExpect(jsonPath('$.series.length()').value(2))
        mvc.perform(get('/api/reports/heartbeat/series').param('service','order').param('metric','missing'))
            .andExpect(status().isBadRequest())
    }

    def "历史折叠保留不同标签身份与最后值，不能仅按桶合并"() {
        given:
        def query = Stub(ClickHouseReportQuery) {
            minuteRows(_,_,_,_,_,_,_) >> [
                new ClickHouseReportQuery.BucketRow('order','METRIC','m','','all','','x=1;',time.plusSeconds(60),AggregationLevel.MINUTE,0,0,0,0,0,4d,2,new long[16],60,null,null),
                new ClickHouseReportQuery.BucketRow('order','METRIC','m','','all','','x=2;',time.plusSeconds(120),AggregationLevel.MINUTE,0,0,0,0,0,8d,3,new long[16],60,null,null)
            ]
        }
        def port = new ClickHouseReportDataPort(query,new DefaultTimeBucketResolver(),{ZoneOffset.UTC})
        when:
        def rows = port.rows('METRIC','order','m',null,time,time.plusSeconds(600),Granularity.MINUTE_10,[])
        then:
        rows.size() == 2
        rows*.key()*.getMetricLabels() as Set == ['x=1;','x=2;'] as Set
        rows.every { it.bucketStart() == time }
        rows*.valueCount().sum() == 5
    }

    def "月窗口今天以已完成小时和当前内存小时拼接，不重复昨天日桶"() {
        given:
        def today = time.truncatedTo(java.time.temporal.ChronoUnit.DAYS)
        def key = SeriesKey.of('order',SeriesKind.HEARTBEAT,'jvm','heap-used','one')
        def yesterday = new ClickHouseReportQuery.BucketRow('order','HEARTBEAT','jvm','heap-used','one','','',today.minusSeconds(86400),AggregationLevel.DAY,0,0,0,0,0,5d,1,new long[16],86400,5d,today.minusSeconds(5))
        def earlier = new ClickHouseReportQuery.BucketRow('order','HEARTBEAT','jvm','heap-used','one','','',today,AggregationLevel.HOUR,0,0,0,0,0,10d,1,new long[16],3600,10d,today.plusSeconds(30))
        def query = Mock(ClickHouseReportQuery)
        def buckets = new DefaultTimeBucketResolver()
        def clock = Clock.fixed(time.plusSeconds(30),ZoneOffset.UTC)
        def history = new ClickHouseReportDataPort(query,buckets,{ZoneOffset.UTC},clock)
        def memory = new InMemoryHourlyReportStore()
        memory.addValue(key,time,20d)
        def router = new ReportDataPortRouter(history,new HourlyReportDataPort(memory,buckets,{ZoneOffset.UTC}),clock,{ZoneOffset.UTC})
        when:
        def rows = router.rows('HEARTBEAT','order','jvm','heap-used',today.minusSeconds(86400),today.plusSeconds(86400),Granularity.DAY_1,['one'])
        then:
        1 * query.dayRows(_,_,_,_,_,today.minusSeconds(86400),today) >> [yesterday]
        1 * query.hourRows(_,_,_,_,_,today,time) >> [earlier]
        rows.findAll { it.bucketStart() == today }*.valueLast().max() == 20d
        rows.size() == 3
    }

    def "Metric 专用来源保留原小时，不让日折叠改变标签归属判断"() {
        given:
        def today = time.truncatedTo(java.time.temporal.ChronoUnit.DAYS)
        def query = Stub(ClickHouseReportQuery) {
            hourRows(_,_,_,_,_,_,_) >> [
                new ClickHouseReportQuery.BucketRow('order','METRIC','m','','all','','city=上海;',time.minusSeconds(3600),AggregationLevel.HOUR,0,0,0,0,0,6d,2,new long[16],3600,null,null)
            ]
        }
        def port = new ClickHouseReportDataPort(query,new DefaultTimeBucketResolver(),{ZoneOffset.UTC},Clock.fixed(time,ZoneOffset.UTC))
        expect:
        port.metricSourceRows('order','m',today,time,Granularity.DAY_1)[0].bucketStart() == time.minusSeconds(3600)
    }
}
