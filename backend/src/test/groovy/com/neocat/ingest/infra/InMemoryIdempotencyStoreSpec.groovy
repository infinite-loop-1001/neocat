package com.neocat.ingest.infra

import spock.lang.Specification

import java.time.Duration

class InMemoryIdempotencyStoreSpec extends Specification {
    def 'production in-process store remembers fingerprints by message ID'() {
        given:
        def store = new InMemoryIdempotencyStore()

        when:
        store.remember('m1', 'fp1', Duration.ofMinutes(120))
        store.remember('m2', 'fp2', Duration.ofMinutes(120))

        then:
        store.fingerprintOf('m1') == 'fp1'
        store.fingerprintOf('m2') == 'fp2'
        store.fingerprintOf('missing') == null
        store.size() == 2
    }
}
