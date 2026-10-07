package com.neocat.dashboard.domain.card

import com.neocat.dashboard.domain.access.OrgAccessGateway
import com.neocat.dashboard.domain.dashboard.Dashboard
import com.neocat.dashboard.domain.dashboard.DashboardRepository
import com.neocat.dashboard.domain.event.CardEvent
import com.neocat.dashboard.domain.event.CardEventPublisher

import com.neocat.common.error.NeocatException
import com.neocat.query.domain.stat.Stat
import spock.lang.Specification

import static com.neocat.common.error.ErrorCode.*

class CardAlertLinkSpec extends Specification {
    DashboardRepository repository = Mock()
    OrgAccessGateway access = Mock()
    CardEventPublisher events = Mock()
    CardService service = new CardService(repository, access, events)

    static Dashboard dashboard() { new Dashboard(3L, 7L, '订单', 0) }

    static Card card(long id = 12L, String formula = 'failures / hits', String name = '/a') {
        Card.withoutThresholds(id, 3L, 'order', 'TRANSACTION', 'URL', name,
                null, [], formula, 'RECENT_24H', 0)
    }

    def 'creating a valid card saves the expected dashboard target and formula'() {
        when:
        def created = service.createCard(100L, 3L, card(0L))

        then:
        1 * repository.findById(3L) >> dashboard()
        1 * access.isEffectiveMember(100L, 7L) >> true
        1 * repository.saveCard({ it.getDashboardId() == 3L && it.getFormula() == 'failures / hits' }) >> card()
        created.getId() == 12L
    }

    def 'invalid formula is rejected before writing a card'() {
        when:
        service.createCard(100L, 3L, card(0L, 'hits + tp99'))

        then:
        1 * repository.findById(3L) >> dashboard()
        1 * access.isEffectiveMember(100L, 7L) >> true
        0 * repository.saveCard(_)
        def error = thrown(NeocatException)
        error.code() == UNIT_MISMATCH
    }

    def 'nonmember cannot create or read another leaf card'() {
        when:
        service.createCard(999L, 3L, card(0L))

        then:
        1 * repository.findById(3L) >> dashboard()
        1 * access.isEffectiveMember(999L, 7L) >> false
        0 * repository.saveCard(_)
        def error = thrown(NeocatException)
        error.code() == NOT_ORG_MEMBER
    }

    def 'changing the formula publishes a card-target event with updated dependencies'() {
        when:
        def updated = service.updateCard(100L, 12L, card(12L, 'tp99 - avgDuration'))

        then:
        1 * repository.findCard(12L) >> card()
        2 * repository.findById(3L) >> dashboard()
        1 * access.isEffectiveMember(100L, 7L) >> true
        1 * repository.saveCard({ it.getFormula() == 'tp99 - avgDuration' }) >> card(12L, 'tp99 - avgDuration')
        1 * events.publish({ it instanceof CardEvent.CardTargetChanged && it.getCardId() == 12L
                && it.getAffectedStats() as Set == ['TP99', 'AVG'] as Set })
        updated.getFormula() == 'tp99 - avgDuration'
    }

    def 'changing only the time range does not invalidate alerts'() {
        given:
        def draft = Card.withoutThresholds(12L, 3L, 'order', 'TRANSACTION', 'URL', '/a',
                null, [], 'failures / hits', 'RECENT_1H', 0)

        when:
        service.updateCard(100L, 12L, draft)

        then:
        1 * repository.findCard(12L) >> card()
        1 * repository.findById(3L) >> dashboard()
        1 * access.isEffectiveMember(100L, 7L) >> true
        1 * repository.saveCard(_) >> draft
        0 * events.publish(_)
    }

    def 'deleting a card publishes the removed target and its referenced stats'() {
        when:
        service.deleteCard(100L, 12L)

        then:
        1 * repository.findCard(12L) >> card()
        2 * repository.findById(3L) >> dashboard()
        1 * access.isEffectiveMember(100L, 7L) >> true
        1 * repository.deleteCard(12L)
        1 * events.publish({ it instanceof CardEvent.CardDeleted && it.getOrgId() == 7L
                && it.getRemovedTargetIdentity() == card().targetIdentity()
                && it.getAffectedStats() as Set == ['FAILURES', 'HITS'] as Set })
    }

    def 'nonmember cannot delete a card'() {
        when:
        service.deleteCard(999L, 12L)

        then:
        1 * repository.findCard(12L) >> card()
        1 * repository.findById(3L) >> dashboard()
        1 * access.isEffectiveMember(999L, 7L) >> false
        0 * repository.deleteCard(_)
        0 * events.publish(_)
        thrown(NeocatException)
    }

    def 'selectable raw stats and card result come only from the leaf cards'() {
        when:
        def targets = service.alertableTargets(100L, 7L)

        then:
        1 * access.isEffectiveMember(100L, 7L) >> true
        1 * repository.byOrg(7L) >> [dashboard()]
        1 * repository.cardsOf(3L) >> [card(), card(13L, 'hits', '/a')]
        targets.findAll { it.getKind() == AlertableTargetKind.CARD_RESULT }*.getCardId() == [12L, 13L]
        targets.findAll { it.getKind() == AlertableTargetKind.RAW_STAT }
                .collectMany { it.getStats() } as Set == [Stat.FAILURES, Stat.HITS] as Set
    }

    def 'a raw target is still referenced by another card in this leaf'() {
        given:
        def target = new AlertableTarget(AlertableTargetKind.RAW_STAT, 0L, 'order',
                'TRANSACTION', 'URL', '/a', null, [Stat.HITS])

        when:
        def stillUsed = service.stillReferenced(7L, target)

        then:
        1 * repository.byOrg(7L) >> [dashboard()]
        1 * repository.cardsOf(3L) >> [card(13L, 'hits', '/a')]
        stillUsed
    }

    def 'a reference on a different leaf cannot keep this leaf target alive'() {
        given:
        def target = new AlertableTarget(AlertableTargetKind.RAW_STAT, 0L, 'order',
                'TRANSACTION', 'URL', '/a', null, [Stat.HITS])

        when:
        def stillUsed = service.stillReferenced(7L, target)

        then:
        1 * repository.byOrg(7L) >> []
        0 * repository.cardsOf(_)
        !stillUsed
    }
}
