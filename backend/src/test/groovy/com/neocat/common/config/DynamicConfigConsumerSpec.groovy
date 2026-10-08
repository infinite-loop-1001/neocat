package com.neocat.common.config

import com.neocat.ingest.config.IngestConfig
import com.neocat.analysis.config.MetricConfig

import com.neocat.ingest.domain.idempotency.*
import com.neocat.ingest.domain.validation.*
import com.neocat.ingest.domain.receive.IngestBatch
import com.neocat.ingest.infra.IngestDropQueue
import com.neocat.analysis.infra.store.InMemoryMetricHourRank
import com.neocat.analysis.domain.bucket.SeriesKey
import spock.lang.Specification
import java.time.*

class DynamicConfigConsumerSpec extends Specification {
    def "同一校验器和迟到策略每次直接读静态配置"() {
        given:
        def validator = new TreeValidator()
        def policy = new LatenessPolicy()
        def batch = new IngestBatch('1.0', [IngestValidationSpec.tree()])
        def now = Instant.parse('2026-10-03T12:30:00Z')

        expect:
        validator.validateBatch(batch, 100).valid
        policy.acceptable(now.minusSeconds(3600).toEpochMilli(), now, ZoneOffset.UTC)

        when:
        IngestConfig.MAX_BATCH_BYTES = 99
        IngestConfig.ACCEPT_LATE_HOURS = 1

        then:
        !validator.validateBatch(batch, 100).valid
        !policy.acceptable(now.minusSeconds(3600).toEpochMilli(), now, ZoneOffset.UTC)
    }

    def "同一幂等服务记新条目时使用更新后的窗口"() {
        given:
        def store = Mock(IdempotencyStore)
        store.fingerprintOf(_) >> null
        def service = new IdempotencyService(store, HistoricalFingerprintLookup.empty())

        when:
        service.decide('one', 'f')

        then:
        1 * store.remember('one', 'f', Duration.ofMinutes(120))

        when:
        IngestConfig.IDEMPOTENCY_WINDOW_MINUTES = 3
        service.decide('two', 'f')

        then:
        1 * store.remember('two', 'f', Duration.ofMinutes(3))
    }

    def "队列扩容即生效，缩容不丢已接收数据，不创建容量副本"() {
        given:
        IngestConfig.QUEUE_CAPACITY = 2
        def queue = new IngestDropQueue<String>()

        expect:
        queue.offer('a')
        queue.offer('b')
        !queue.offer('c')

        when:
        IngestConfig.QUEUE_CAPACITY = 3

        then:
        queue.offer('c')
        queue.capacity() == 3

        when:
        IngestConfig.QUEUE_CAPACITY = 1

        then:
        queue.size() == 3
        queue.capacity() == 1
        !queue.offer('d')
        queue.watermark() == 1.0
        queue.pollBatch(3, 0) == ['a', 'b', 'c']
        queue.offer('e')
        queue.droppedCount() == 2
    }

    def "Top N 更新影响后续决策，不改变已经固化的小时归属"() {
        given:
        MetricConfig.TOP_N = 1
        def rank = new InMemoryMetricHourRank()
        def hour = Instant.parse('2026-10-03T00:00:00Z')
        rank.record('s', 'm', 'a', hour)
        rank.record('s', 'm', 'b', hour)
        rank.finalizeHour(hour)

        when:
        MetricConfig.TOP_N = 2
        rank.finalizeHour(hour)

        then:
        rank.record('s', 'm', 'b', hour) == SeriesKey.OTHER_LABELS
        rank.record('s', 'm', 'a', hour.plusSeconds(3600)) == 'a'
        rank.record('s', 'm', 'b', hour.plusSeconds(3600)) == 'b'
    }
}
