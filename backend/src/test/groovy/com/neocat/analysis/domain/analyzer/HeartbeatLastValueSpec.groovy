package com.neocat.analysis.domain.analyzer

import com.neocat.analysis.domain.bucket.AggregatedRow
import com.neocat.analysis.domain.bucket.AggregationLevel
import com.neocat.analysis.domain.bucket.AggregationRoller
import com.neocat.analysis.domain.bucket.MinuteBucket
import com.neocat.analysis.domain.bucket.SeriesKey
import com.neocat.analysis.domain.bucket.SeriesKind

import spock.lang.Specification
import java.time.Instant
import com.neocat.analysis.infra.store.InMemoryHourlyReportStore
import com.neocat.ingest.domain.tree.HeartbeatValue
import com.neocat.ingest.domain.tree.MessageTree
import com.neocat.ingest.domain.tree.RawNode

class HeartbeatLastValueSpec extends Specification {
    def "新载荷仅写有效指标，未定义不上报而合法零入桶，每实例独立"() {
        given:
        def store = new InMemoryHourlyReportStore()
        def analyzer = new HeartbeatAnalyzer(store)
        def time = Instant.parse('2026-10-02T10:00:00Z')
        def original = AnalysisFixtures.heartbeatTree('order','one',time.toEpochMilli())
        def node = original.getNodes()[0]
        def payload = new HeartbeatValue(['young-used': 0L, 'metaspace-max': -1L, 'full-gc-count': 2L])
        def replacement = new RawNode(node.getNodeId(),node.getKind(),node.getCategory(),node.getName(),node.getStatus(),node.getTimestamp(),node.getDurationMs(),node.getParentNodeId(),node.getMetric(),payload,node.getRemoteCall(),node.getException(),node.getTags())
        when:
        analyzer.analyze(new MessageTree(original.getServiceName(),original.getInstanceId(),original.getMessageId(),original.getRootMessageId(),original.getParentMessageId(),original.getTreeTimestamp(),[replacement]))
        then:
        store.seriesKeys().size() == 2
        store.seriesKeys().every { it.getInstance() == 'one' }
        store.bucket(SeriesKey.of('order',SeriesKind.HEARTBEAT,'jvm','young-used','one'),time).valueLast() == 0d
    }
    def "最后采样按事件时间选择而不是求和或最大值，乱序与重启归零正确"() {
        given:
        def bucket = new MinuteBucket()
        def first = Instant.parse('2026-10-02T10:00:10Z')
        def last = first.plusSeconds(20)
        when:
        bucket.addValue(100, first)
        bucket.addValue(0, last)
        bucket.addValue(200, first.plusSeconds(5))
        then:
        bucket.valueLast() == 0d
        bucket.valueLastTime() == last
        bucket.valueCount() == 3
    }

    def "粗桶传递最后观测时间，未提供最后值的旧桶不冒充最后值"() {
        given:
        def key = SeriesKey.of('order', SeriesKind.HEARTBEAT, 'jvm', 'gc-count', 'one')
        def time = Instant.parse('2026-10-02T10:00:00Z')
        def a = new AggregatedRow(key, time, AggregationLevel.MINUTE, 60)
        a.addValue(90d, 1)
        a.mergeLastValue(90d, time.plusSeconds(30))
        def b = new AggregatedRow(key, time.plusSeconds(60), AggregationLevel.MINUTE, 60)
        b.addValue(0d, 1)
        b.mergeLastValue(0d, time.plusSeconds(90))
        when:
        def result = new AggregationRoller().roll([b, a], AggregationLevel.HOUR)
        then:
        result.size() == 1
        result[0].valueLast() == 0d
        result[0].valueLastTime() == time.plusSeconds(90)
        new AggregatedRow(key, time, AggregationLevel.HOUR, 3600).valueLast() == null
    }
}
