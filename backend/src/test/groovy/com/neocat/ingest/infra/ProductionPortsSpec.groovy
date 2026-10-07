package com.neocat.ingest.infra

import com.neocat.ingest.domain.receive.QualityEventSink
import com.neocat.ingest.domain.receive.QualityType
import com.neocat.trace.api.internal.StoredFingerprint
import com.neocat.trace.domain.tree.RawTreeStore
import com.neocat.trace.infra.adapter.StoredFingerprintService
import org.springframework.jdbc.core.JdbcTemplate
import spock.lang.Specification

import java.time.Instant

class ProductionPortsSpec extends Specification {
    def 'historical fingerprint is read from the persisted trace, not a default empty result'() {
        given:
        def store = Mock(RawTreeStore)
        def stored = new StoredFingerprintService(store)
        StoredFingerprint lookup = stored

        when:
        def result = new HistoricalFingerprintAdapter(lookup).fingerprintOf('m1')

        then:
        1 * store.fingerprintOf('m1') >> 'hash'
        result == 'hash'
    }

    def 'quality event is written to the ClickHouse quality table with explicit count'() {
        given:
        def jdbc = Mock(JdbcTemplate)
        def sink = new JdbcQualityEventSink(jdbc)
        def at = Instant.parse('2026-09-24T04:00:00Z')

        when:
        sink.record(QualityType.QUEUE_FULL, 'order', 'm1', 'full', at)

        then:
        1 * jdbc.update({ it.contains('neocat.nc_quality_event') && it.contains('VALUES (?, ?, ?, ?, ?, 1)') },
                java.sql.Timestamp.from(at), 'QUEUE_FULL', 'order', 'm1', 'full') >> 1
    }
}
