package com.neocat.trace.infra.clickhouse

import com.neocat.trace.domain.tree.TraceNode
import com.neocat.trace.domain.tree.TraceTree
import spock.lang.Specification

import java.time.Instant
import com.neocat.trace.infra.clickhouse.row.TraceTreeRow

/** The adapter is under test; ClickHouse queries are isolated at their interface. */
class ClickHouseRawTreeStoreSpec extends Specification {
    static final Instant NOW = Instant.parse('2026-09-24T04:00:00Z')

    RawTreeQuery query = Mock()
    TreePayloadCodec codec = Mock()
    ClickHouseRawTreeStore store = new ClickHouseRawTreeStore(query, codec)

    static TraceTree tree(String id, String root, String parent) {
        new TraceTree(id, root, parent, 'order', 'order-ip', NOW.toEpochMilli(),
                'fp-' + id, [new TraceNode('n1', 'TRANSACTION', 'URL', '/a', '0',
                        NOW.toEpochMilli(), 12L, null, [:])])
    }

    static TraceTreeRow row(String id, String root, String parent) {
        new TraceTreeRow('order', 'order-ip', id, root, parent, NOW, 'fp-' + id, 'encoded')
    }

    def 'saving a tree writes its payload and relation, normalizing a missing parent'() {
        given:
        def input = tree('m1', 'm1', null)

        when:
        store.save(input)

        then:
        1 * codec.encode(input.getNodes()) >> 'encoded'
        1 * query.insertTree({ it.getMessageId() == 'm1' && it.getParentMessageId() == '' &&
                it.getFingerprint() == 'fp-m1' && it.getPayload() == 'encoded' })
        1 * query.insertRelation({ it.getMessageId() == 'm1' && it.getRootMessageId() == 'm1' &&
                it.getParentMessageId() == '' })
    }

    def 'a returned row is decoded without losing service, parent, timestamp or fingerprint'() {
        given:
        def node = tree('m1', 'root', 'parent').getNodes()[0]

        when:
        def result = store.findByMessageId('m1')

        then:
        1 * query.selectTree('m1') >> row('m1', 'root', 'parent')
        1 * codec.decode('encoded') >> [node]
        result.getMessageId() == 'm1'
        result.getParentMessageId() == 'parent'
        result.getRootMessageId() == 'root'
        result.getNodes() == [node]
        result.getFingerprint() == 'fp-m1'
    }

    def 'expired tree eviction never deletes the relation index'() {
        given:
        def threshold = NOW.minusSeconds(7 * 86400)

        when:
        def evicted = store.evictTreesOlderThan(threshold)
        def existed = store.everExisted('old')
        def tree = store.findByMessageId('old')

        then:
        1 * query.deleteTreesOlderThan(threshold) >> ['old']
        1 * query.existsRelation('old') >> true
        1 * query.selectTree('old') >> null
        evicted == ['old']
        existed
        tree == null
    }

    def 'fingerprint lookup reads persisted tree instead of an in-process cache'() {
        when:
        def present = store.fingerprintOf('m1')
        def missing = store.fingerprintOf('missing')

        then:
        1 * query.selectTree('m1') >> row('m1', 'm1', '')
        1 * query.selectTree('missing') >> null
        present == 'fp-m1'
        missing == null
    }

    def 'service and time filters are passed through as exclusive range boundaries'() {
        given:
        def from = NOW.minusSeconds(3600)

        when:
        def trees = store.findByServiceAndTimeRange('order', from.toEpochMilli(), NOW.toEpochMilli())

        then:
        1 * query.selectTreesByServiceAndRange('order', from, NOW) >> []
        trees.isEmpty()
    }
}
