package com.neocat.dashboard.domain.dashboard

import com.neocat.dashboard.domain.access.OrgAccessGateway
import com.neocat.dashboard.domain.card.Card
import com.neocat.dashboard.domain.event.CardEventPublisher

import com.neocat.common.error.NeocatException
import spock.lang.Specification

import static com.neocat.common.error.ErrorCode.*
import com.neocat.dashboard.domain.event.CardDeleted

class DashboardPermissionSpec extends Specification {
    DashboardRepository repository = Mock()
    OrgAccessGateway access = Mock()
    DashboardService service = new DashboardService(repository, access)

    static Dashboard dashboard() { new Dashboard(12L, 7L, '订单大盘', 0) }

    def 'effective member of a leaf can create its dashboard'() {
        when:
        def created = service.create(100L, 7L, '订单大盘')

        then:
        1 * access.isLeaf(7L) >> true
        1 * access.isEffectiveMember(100L, 7L) >> true
        1 * repository.save(new Dashboard(0L, 7L, '订单大盘', 0)) >> dashboard()
        created == dashboard()
    }

    def 'a nonmember, including an administrator, cannot create on another leaf'() {
        when:
        service.create(1L, 7L, '管理员大盘')

        then:
        1 * access.isLeaf(7L) >> true
        1 * access.isEffectiveMember(1L, 7L) >> false
        0 * repository.save(_)
        def error = thrown(NeocatException)
        error.code() == NOT_ORG_MEMBER
    }

    def 'non-leaf organizations cannot have dashboards'() {
        when:
        service.create(100L, 7L, '订单大盘')

        then:
        1 * access.isLeaf(7L) >> false
        0 * repository.save(_)
        def error = thrown(NeocatException)
        error.code() == NOT_LEAF
    }

    def 'a member sees the leaf dashboards'() {
        when:
        def listed = service.list(100L, 7L)

        then:
        1 * access.isLeaf(7L) >> true
        1 * access.isEffectiveMember(100L, 7L) >> true
        1 * repository.byOrg(7L) >> [dashboard()]
        listed == [dashboard()]
    }

    def 'a nonmember sees no dashboard names or entry point'() {
        when:
        def listed = service.list(200L, 7L)

        then:
        1 * access.isLeaf(7L) >> true
        1 * access.isEffectiveMember(200L, 7L) >> false
        0 * repository.byOrg(_)
        listed.isEmpty()
    }

    def 'guessing a dashboard ID cannot bypass membership checks'() {
        when:
        service.requireAccessible(200L, 12L)

        then:
        1 * repository.findById(12L) >> dashboard()
        1 * access.isEffectiveMember(200L, 7L) >> false
        def error = thrown(NeocatException)
        error.code() == NOT_ORG_MEMBER
    }

    def 'an accessible dashboard is returned and a nonexistent one is rejected'() {
        when:
        def found = service.requireAccessible(100L, 12L)

        then:
        1 * repository.findById(12L) >> dashboard()
        1 * access.isEffectiveMember(100L, 7L) >> true
        found == dashboard()

        when:
        service.requireAccessible(100L, 9999L)

        then:
        1 * repository.findById(9999L) >> null
        def error = thrown(NeocatException)
        error.code() == DASHBOARD_NOT_FOUND
    }

    def 'renaming rechecks membership before saving'() {
        when:
        def renamed = service.rename(100L, 12L, '新名字')

        then:
        1 * repository.findById(12L) >> dashboard()
        1 * access.isEffectiveMember(100L, 7L) >> true
        1 * repository.save(new Dashboard(12L, 7L, '新名字', 0)) >>
                new Dashboard(12L, 7L, '新名字', 0)
        renamed.getName() == '新名字'
    }

    def 'previous access does not authorize a subsequent rename after revocation'() {
        when:
        service.rename(100L, 12L, '新名字')

        then:
        1 * repository.findById(12L) >> dashboard()
        1 * access.isEffectiveMember(100L, 7L) >> false
        0 * repository.save(_)
        def error = thrown(NeocatException)
        error.code() == NOT_ORG_MEMBER
    }

    def 'deletion requires membership on each request'() {
        when:
        service.delete(100L, 12L)

        then:
        1 * repository.findById(12L) >> dashboard()
        1 * access.isEffectiveMember(100L, 7L) >> true
        1 * repository.delete(12L)

        when:
        service.delete(200L, 12L)

        then:
        1 * repository.findById(12L) >> dashboard()
        1 * access.isEffectiveMember(200L, 7L) >> false
        0 * repository.delete(_)
        thrown(NeocatException)
    }

    def 'deleting a dashboard publishes deletion for each removed card in the same use case'() {
        given:
        def events = Mock(CardEventPublisher)
        def service = new DashboardService(repository, access, events)
        def card = Card.withoutThresholds(22L, 12L, 'order', 'TRANSACTION', 'URL', '/a',
                null, [], 'failures / hits', 'RECENT_1H', 0)

        when:
        service.delete(100L, 12L)

        then:
        1 * repository.findById(12L) >> dashboard()
        1 * access.isEffectiveMember(100L, 7L) >> true
        1 * repository.cardsOf(12L) >> [card]
        1 * repository.delete(12L)
        1 * events.publish({ it instanceof CardDeleted && it.getOrgId() == 7L
                && it.getCardId() == 22L && it.getAffectedStats() as Set == ['FAILURES', 'HITS'] as Set })
    }

    def 'listAll only queries effective leaves, not arbitrary organizations'() {
        when:
        def listed = service.listAll(100L)

        then:
        1 * access.effectiveLeaves(100L) >> ([7L] as Set)
        1 * repository.byOrg(7L) >> [dashboard()]
        listed == [dashboard()]
    }
}
