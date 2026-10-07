package com.neocat.client

import spock.lang.Specification
import java.lang.management.*

class JvmHeartbeatSamplerSpec extends Specification {
    def "缺失不是零，负数不可用，手动可上报 Full GC"() {
        when:
        def hb = JvmHeartbeatSampler.payload([gcCount: '0', youngUsed: '12', metaspaceMax: '-1', fullGcCount: '3', heapMax: 'bad'])
        then:
        hb.presenceAware
        hb.hasGcCount() && hb.gcCount == 0
        hb.hasYoungUsedBytes() && hb.youngUsedBytes == 12
        hb.hasFullGcCount() && hb.fullGcCount == 3
        !hb.hasHeapUsedBytes()
        !hb.hasMetaspaceMaxBytes()
        !hb.hasHeapMaxBytes()
    }

    def "池和收集器不能识别时缺失；元空间无上限不填零，old 不等于 full"() {
        given:
        def memory = Stub(MemoryMXBean) { getHeapMemoryUsage() >> new MemoryUsage(0, 10, 20, 30) }
        def threads = Stub(ThreadMXBean) { getThreadCount() >> 4 }
        def pool = Stub(MemoryPoolMXBean) {
            getName() >> 'Metaspace'; isValid() >> true; getUsage() >> new MemoryUsage(0, 8, 10, -1)
        }
        def collector = Stub(GarbageCollectorMXBean) {
            getName() >> 'G1 Old Generation'; getCollectionCount() >> 0; getCollectionTime() >> 0
        }
        when:
        def result = JvmHeartbeatSampler.sample(memory, threads, [pool], [collector])
        then:
        result.heapUsed == '10'
        result.metaspaceUsed == '8'
        !result.containsKey('metaspaceMax')
        result.oldGcCount == '0'
        !result.containsKey('fullGcCount')
        JvmHeartbeatSampler.partition('unknown') == null
        JvmHeartbeatSampler.collectorKind('ZGC Cycles') == null
    }
}
