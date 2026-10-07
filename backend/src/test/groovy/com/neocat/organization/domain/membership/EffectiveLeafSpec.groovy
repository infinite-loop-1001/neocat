package com.neocat.organization.domain.membership

import com.neocat.organization.domain.tree.OrgNode
import com.neocat.organization.domain.tree.OrgNodeRepository

import com.neocat.common.error.NeocatException
import spock.lang.Specification
import com.neocat.organization.api.internal.EffectiveMembershipChanged
import org.springframework.context.ApplicationEventPublisher

import static com.neocat.common.error.ErrorCode.ORG_NOT_FOUND

class EffectiveLeafSpec extends Specification {
    OrgNodeRepository nodes = Stub()
    MembershipRepository memberships = Mock()
    EffectiveLeafRepository leaves = Mock()
    OrgMembershipService service = new OrgMembershipService(nodes, memberships, leaves)

    static List<OrgNode> tree() {
        [new OrgNode(1L, '总部', null), new OrgNode(2L, '支付部', 1L),
         new OrgNode(3L, '支付组', 2L), new OrgNode(4L, '订单组', 1L)]
    }

    def 'adding a member to a leaf grants only that leaf'() {
        given:
        nodes.findById(3L) >> tree()[2]
        nodes.findAll() >> tree()

        when:
        service.addMember(3L, 100L)

        then:
        1 * memberships.add(3L, 100L)
        1 * memberships.orgsOf(100L) >> ([3L] as Set)
        1 * leaves.replaceAll(100L, [3L] as Set)
    }

    def 'adding a member to an ancestor grants all descendant leaves'() {
        given:
        nodes.findById(1L) >> tree()[0]
        nodes.findAll() >> tree()

        when:
        service.addMember(1L, 100L)

        then:
        1 * memberships.add(1L, 100L)
        1 * memberships.orgsOf(100L) >> ([1L] as Set)
        1 * leaves.replaceAll(100L, [3L, 4L] as Set)
    }

    def 'multiple membership paths to the same leaf are deduplicated after revocation'() {
        given:
        nodes.findAll() >> tree()

        when:
        service.removeMember(2L, 100L)

        then:
        1 * memberships.remove(2L, 100L)
        1 * memberships.orgsOf(100L) >> ([3L, 1L] as Set)
        1 * leaves.replaceAll(100L, [3L, 4L] as Set)
    }

    def 'removing the last membership revokes every leaf immediately'() {
        when:
        service.removeMember(3L, 100L)

        then:
        1 * memberships.remove(3L, 100L)
        1 * memberships.orgsOf(100L) >> ([] as Set)
        1 * leaves.replaceAll(100L, [] as Set)
    }

    def 'turning a former leaf into a parent moves its effective permission to the new leaf'() {
        given:
        nodes.findAll() >> (tree() + new OrgNode(5L, '支付小组', 3L))

        when:
        service.recompute(100L)

        then:
        1 * memberships.orgsOf(100L) >> ([3L] as Set)
        1 * leaves.replaceAll(100L, [5L] as Set)
    }

    def 'direct member counts and effective leaf membership use distinct repositories'() {
        when:
        def direct = service.effectiveMembers(3L)
        def effective = service.isEffectiveMember(100L, 3L)

        then:
        1 * memberships.membersOf(3L) >> ([200L] as Set)
        1 * leaves.leavesOf(100L) >> ([3L] as Set)
        direct == [200L] as Set
        effective
    }

    def 'missing organization is rejected before any membership write'() {
        given:
        nodes.findById(9999L) >> null

        when:
        service.addMember(9999L, 100L)

        then:
        0 * memberships.add(_, _)
        def error = thrown(NeocatException)
        error.code() == ORG_NOT_FOUND
    }

    def 'only truly lost effective leaves emit synchronous revocation events'() {
        given:
        def publisher = Mock(ApplicationEventPublisher)
        def service = new OrgMembershipService(nodes, memberships, leaves, publisher)
        nodes.findAll() >> tree()

        when:
        service.removeMember(2L, 100L)

        then:
        1 * memberships.remove(2L, 100L)
        1 * leaves.leavesOf(100L) >> ([3L, 4L] as Set)
        1 * memberships.orgsOf(100L) >> ([3L] as Set)
        1 * leaves.replaceAll(100L, [3L] as Set)
        1 * publisher.publishEvent(new EffectiveMembershipChanged(100L, 4L, false))
        0 * publisher.publishEvent(new EffectiveMembershipChanged(100L, 3L, false))
    }
}
