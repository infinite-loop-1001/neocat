package com.neocat.query.domain.metric

import spock.lang.Specification
import com.fasterxml.jackson.databind.ObjectMapper
import com.neocat.analysis.domain.bucket.*
import com.neocat.analysis.domain.metric.*
import com.neocat.common.time.bucket.Bucket
import com.neocat.common.error.exception.ValidationException
import java.time.Instant
import com.neocat.analysis.infra.store.InMemoryMetricLabelMetadata
import java.time.temporal.ChronoUnit
import com.neocat.analysis.domain.metric.metadata.Entry

class MetricCountBackendSpec extends Specification {
    def time = Instant.parse('2026-10-02T10:00:00Z')
    def json = new ObjectMapper()
    def row(String labels, int count) {
        def row = new AggregatedRow(SeriesKey.metric('order', 'm', labels), time, AggregationLevel.MINUTE, 60)
        row.addValue(10000, count)
        row
    }
    def entry(Map labels, boolean merged = false) {
        new Entry('order', 'm', time, MetricLabels.canonicalize(labels), labels, merged, 1)
    }

    def "全量使用 valueCount 且包含 other 一次，筛选同键 OR 异键 AND"() {
        given:
        def metadata = [entry([channel:'app', city:'上海']), entry([channel:'web', city:'上海']),
                new Entry('order','m',time,'channel=partner;',[channel:'partner'],true,2)]
        def rows = [row(metadata[0].getCanonicalLabels(), 4), row(metadata[1].getCanonicalLabels(), 3), row(SeriesKey.OTHER_LABELS, 2)]
        def buckets = [new Bucket(time, time.plusSeconds(60), false, 60)]
        def service = new MetricCountService()
        expect:
        service.points(rows, metadata, buckets, MetricFilters.parse(null,json), time.plusSeconds(80), {false})[0].value == 9
        service.points(rows, metadata, buckets, MetricFilters.parse('{"channel":["app","web"],"city":["上海"]}',json), time.plusSeconds(80), {false})[0].value == 7
        service.points(rows, metadata, buckets, MetricFilters.parse('{"city":["北京"]}',json), time.plusSeconds(80), {false})[0].quality == 'ZERO'
        service.points(rows, metadata, buckets, MetricFilters.parse('{"channel":["partner"]}',json), time.plusSeconds(80), {false})[0].quality == 'MERGED_OTHER'
        service.points(rows, metadata, buckets, MetricFilters.parse('{"channel":["partner"]}',json), time.plusSeconds(80), {false})[0].value == null
    }

    def "无采集与未来不补零，旧数据标签身份缺失不能冒充准确筛选"() {
        given:
        def buckets = [new Bucket(time,time.plusSeconds(60),false,60)]
        def service = new MetricCountService()
        expect:
        service.points([], [], buckets, MetricFilters.parse(null,json), time.plusSeconds(80), {false})[0].quality == 'NO_DATA'
        service.points([row('',4)], [], buckets, MetricFilters.parse(null,json), time.minusSeconds(1), {false})[0].value == null
        service.points([row('',4)], [], buckets, MetricFilters.parse('{"x":["y"]}',json), time.plusSeconds(80), {false})[0].value == null
        service.points([row('',4)], [], buckets, MetricFilters.parse(null,json), time.plusSeconds(80), {true})[0].quality == 'DROPPED'
    }

    def "非法条件拒绝而非查全量: #raw"() {
        when: MetricFilters.parse(raw,json)
        then: thrown(ValidationException)
        where: raw << ['[]','null','bad','{"x":"y"}','{"x":[1]}','{} {}','{} junk']
    }

    def "标签分隔符不会碰撞，元数据记录实际写桶归属"() {
        given:
        def memory = new InMemoryMetricLabelMetadata()
        when:
        memory.record('order','m',[x:'a;b=c'], SeriesKey.OTHER_LABELS,time)
        then:
        MetricLabels.canonicalize([x:'a;b=c']) != MetricLabels.canonicalize([x:'a',b:'c'])
        memory.entries(time,time.plusSeconds(60))[0].isMerged()
        memory.entries(time,time.plusSeconds(60))[0].getLabels() == [x:'a;b=c']
    }

    def "缺观测次数的旧桶混入历史总量时仍是缺口，不能只显示新桶子集"() {
        given:
        def sources = [row('x=1;',4), row('x=2;',0)]
        def result = new MetricCountService().points(sources,[],[new Bucket(time,time.plusSeconds(60),false,60)],
                MetricFilters.parse(null,json),time.plusSeconds(80),{false})
        expect:
        result[0].quality == 'NO_DATA'
        result[0].value == null
    }

    def "unknown count 随小时和日聚合传递，混有新值也不可恢复"() {
        given:
        def legacy = row('x=1;',0)
        def modern = row('x=1;',4)
        def rolled = new AggregationRoller().roll([legacy,modern],AggregationLevel.DAY)
        def day = time.truncatedTo(ChronoUnit.DAYS)
        expect:
        rolled[0].valueCount() == 4L
        rolled[0].valueCountMissing()
        new MetricCountService().points(rolled,[],[new Bucket(day,day.plusSeconds(86400),false,86400)],
                MetricFilters.parse(null,json),day.plusSeconds(86401),{false})[0].value == null
    }

    def "当前和历史小时聚到同一天仍按真实来源小时判断标签身份"() {
        given:
        def day = time.truncatedTo(ChronoUnit.DAYS)
        def older = new AggregatedRow(SeriesKey.metric('order','m','city=上海;'),time.minusSeconds(3600),AggregationLevel.HOUR,3600)
        older.addValue(9d,3)
        def entries = [new Entry('order','m',time.minusSeconds(3600),'city=上海;',[city:'上海'],false,1)]
        def result = new MetricCountService().points([older],entries,[new Bucket(day,day.plusSeconds(86400),false,86400)],
                MetricFilters.parse('{"city":["上海"]}',json),time.plusSeconds(80),{false})
        expect:
        result[0].value == 3
        result[0].quality == 'REALTIME'
    }

    def "版本化分隔符身份与任何旧 percent 编码身份不会碰撞"() {
        expect:
        MetricLabels.canonicalize([x:'a;b']) != 'x=a%3Bb;'
        MetricLabels.canonicalize([x:'a%3Bb']) != MetricLabels.canonicalize([x:'a;b'])
        MetricLabels.canonicalize([channel:'app']) == 'channel=app;'
    }

    def "other 桶已写而标签元数据仍落后时不显示可见子集"() {
        given:
        def rows = [row('channel=app;',4), row(SeriesKey.OTHER_LABELS,2)]
        def metadata = [entry([channel:'app']),entry([channel:'partner'],true)]
        def result = new MetricCountService().points(rows,metadata,[new Bucket(time,time.plusSeconds(60),false,60)],
                MetricFilters.parse('{"channel":["app"]}',json),time.plusSeconds(80),{false})
        expect:
        result[0].quality == 'MERGED_OTHER'
        result[0].value == null
    }
}
