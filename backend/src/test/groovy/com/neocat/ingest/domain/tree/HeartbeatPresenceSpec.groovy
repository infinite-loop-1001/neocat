package com.neocat.ingest.domain.tree

import com.neocat.ingest.domain.validation.FingerprintCalculator

import spock.lang.Specification
import com.neocat.ingest.infra.protocol.IngestRequestMapper
import com.neocat.protocol.ingest.v1.Heartbeat
import com.neocat.analysis.domain.analyzer.AnalysisFixtures

class HeartbeatPresenceSpec extends Specification {
    def "新的载荷区分缺失和真实零，旧载荷仍兼容五项"() {
        expect:
        IngestRequestMapper.toHeartbeat(Heartbeat.newBuilder().setPresenceAware(true)
            .setGcCount(0).setYoungUsedBytes(12).build()).getValues() == ['gc-count': 0L, 'young-used': 12L]
        IngestRequestMapper.toHeartbeat(Heartbeat.newBuilder().setHeapUsedBytes(22).build()).getValues().size() == 5
        !IngestRequestMapper.toHeartbeat(Heartbeat.newBuilder().setPresenceAware(true)
            .setMetaspaceMaxBytes(-1).build()).getValues().containsKey('metaspace-max')
    }

    def "原字段号 wire 兼容且 presence-aware 显式零经过序列化保留"() {
        given:
        // Old proto3 encoders omit zero scalar fields. Only field #1 is present here.
        def old = Heartbeat.parseFrom([0x08,0x16] as byte[])
        def modern = Heartbeat.parseFrom(Heartbeat.newBuilder().setPresenceAware(true).setGcCount(0).build().toByteArray())
        expect:
        IngestRequestMapper.toHeartbeat(old).getValues() == ['heap-used':22L,'heap-max':0L,'gc-count':0L,'gc-time':0L,threads:0L]
        modern.hasGcCount()
        IngestRequestMapper.toHeartbeat(modern).getValues() == ['gc-count':0L]
        (1..20).collect { Heartbeat.descriptor.findFieldByNumber(it) }.every { it.hasPresence() }
        Heartbeat.descriptor.findFieldByNumber(5).name == 'thread_count'
    }

    def "新指标变化与缺失vs零参与幂等指纹，而旧五项指纹保持稳定"() {
        given:
        def template = AnalysisFixtures.heartbeatTree('order','one',1000L)
        def node = template.getNodes()[0]
        def makeTree = { Map values ->
            def replacement = new RawNode(node.getNodeId(),node.getKind(),node.getCategory(),node.getName(),node.getStatus(),node.getTimestamp(),node.getDurationMs(),node.getParentNodeId(),node.getMetric(),new HeartbeatValue(values),node.getRemoteCall(),node.getException(),node.getTags())
            new MessageTree(template.getServiceName(),template.getInstanceId(),template.getMessageId(),template.getRootMessageId(),template.getParentMessageId(),template.getTreeTimestamp(),[replacement])
        }
        def calculator = new FingerprintCalculator()
        def legacy = new HeartbeatValue(1,2,3,4,5)
        expect:
        calculator.fingerprint(makeTree(['young-used':0L])) != calculator.fingerprint(makeTree([:]))
        calculator.fingerprint(makeTree(['young-used':1L])) != calculator.fingerprint(makeTree(['young-used':2L]))
        calculator.fingerprint(makeTree(legacy.getValues())) == calculator.fingerprint(makeTree(['heap-used':1L,'heap-max':2L,'gc-count':3L,'gc-time':4L,threads:5L]))
    }
}
