package com.neocat.dashboard.infra.adapter

import com.neocat.dashboard.infra.listener.SpringCardEventPublisher

import com.neocat.organization.api.internal.OrganizationAccess
import org.springframework.context.ApplicationEventPublisher
import spock.lang.Specification
import com.neocat.dashboard.domain.event.CardDeleted

class DashboardPortsSpec extends Specification {
    def 'organization permission is delegated to current effective-leaf projection'() {
        given:
        def organizations = Mock(OrganizationAccess)
        def adapter = new OrganizationAccessAdapter(organizations)

        when:
        def member = adapter.isEffectiveMember('keith.ji', 7L)
        def leaf = adapter.isLeaf(7L)
        def accessible = adapter.effectiveLeaves('keith.ji')

        then:
        1 * organizations.isEffectiveMember('keith.ji', 7L) >> true
        1 * organizations.isLeaf(7L) >> true
        1 * organizations.effectiveLeaves('keith.ji') >> ([7L] as Set)
        member && leaf && accessible == ([7L] as Set)
    }

    def 'card events are actually published for the alert listener'() {
        given:
        def publisher = Mock(ApplicationEventPublisher)
        def event = new CardDeleted(10L, 2L, 7L, 'order|TRANSACTION|URL|/a|', [])

        when:
        new SpringCardEventPublisher(publisher).publish(event)

        then:
        1 * publisher.publishEvent(event)
    }
}
