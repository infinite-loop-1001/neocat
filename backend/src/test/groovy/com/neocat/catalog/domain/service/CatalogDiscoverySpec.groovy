package com.neocat.catalog.domain.service

import com.neocat.catalog.domain.entry.InstanceEntry
import com.neocat.catalog.domain.entry.ServiceEntry
import com.neocat.catalog.domain.report.SeriesPresence

import spock.lang.Specification

import java.time.Instant

class CatalogDiscoverySpec extends Specification {
    static final Instant NOW = Instant.parse('2026-09-24T04:00:00Z')

    CatalogRepository repository = Mock()
    SeriesPresence presence = Stub()
    CatalogService service = new CatalogService(repository, presence)

    def 'first report upserts the service at its event time'() {
        given:
        def entry = new ServiceEntry('order', NOW, NOW)

        when:
        def result = service.ensureService('order', NOW)

        then:
        1 * repository.upsertService('order', NOW) >> entry
        result == entry
    }

    def 'repeated service discovery relies on repository upsert without creating a duplicate'() {
        given:
        def later = NOW.plusSeconds(600)
        def original = new ServiceEntry('order', NOW, NOW)
        def updated = new ServiceEntry('order', NOW, later)

        when:
        def first = service.ensureService('order', NOW)
        def second = service.ensureService('order', later)

        then:
        1 * repository.upsertService('order', NOW) >> original
        1 * repository.upsertService('order', later) >> updated
        first == original
        second == updated
    }

    def 'discovery upserts service before instance'() {
        when:
        service.discover('order', '10.0.0.8', NOW)

        then:
        1 * repository.upsertService('order', NOW) >> new ServiceEntry('order', NOW, NOW)
        then:
        1 * repository.upsertInstance('order', '10.0.0.8', NOW) >>
                new InstanceEntry('order', '10.0.0.8', NOW, NOW)
    }

    def 'invalid service name is rejected without touching persistence'() {
        when:
        service.ensureService(null, NOW)

        then:
        thrown(IllegalArgumentException)
        0 * repository._
    }

    def 'blank instance ID is rejected without touching persistence'() {
        when:
        service.ensureInstance('order', ' ', NOW)

        then:
        thrown(IllegalArgumentException)
        0 * repository._
    }

    def 'unknown service is absent from the repository listing'() {
        when:
        def exists = service.exists('ghost')

        then:
        1 * repository.services() >> []
        !exists
    }
}
