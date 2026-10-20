package com.neocat.organization.domain.membership

import com.neocat.organization.domain.tree.OrgNode
import com.neocat.organization.domain.tree.OrgNodeRepository

import com.neocat.common.error.NeocatException
import com.neocat.identity.api.internal.AccountDirectory
import spock.lang.Specification
import com.neocat.organization.api.internal.EffectiveMembershipChanged
import org.springframework.context.ApplicationEventPublisher

import static com.neocat.common.error.ErrorCode.ORG_NOT_FOUND
import static com.neocat.common.error.ErrorCode.USER_NOT_FOUND

class EffectiveLeafSpec extends Specification {
    OrgNodeRepository nodes = Stub()
    MembershipRepository memberships = Mock()
    EffectiveLeafRepository leaves = Mock()
    AccountDirectory accounts = Stub()
    OrgMembershipService service = new OrgMembershipService(nodes, memberships, leaves, null, accounts)

    def setup() {
        accounts.exists('keith.ji') >> true
    }

    static List<OrgNode> tree() {
        [new OrgNode(1L, '总部', null), new OrgNode(2L, '支付部', 1L),
         new OrgNode(3L, '支付组', 2L), new OrgNode(4L, '订单组', 1L)]
    }

    def 'adding a member to a leaf grants only that leaf'() {
        given:
        nodes.findById(3L) >> tree()[2]
        nodes.findAll() >> tree()

        when:
        service.addMember(3L, 'keith.ji')

        then:
        1 * memberships.add(3L, 'keith.ji')
        1 * memberships.orgsOf('keith.ji') >> ([3L] as Set)
        1 * leaves.replaceAll('keith.ji', [3L] as Set)
    }

    def 'adding a member to an ancestor grants all descendant leaves'() {
        given:
        nodes.findById(1L) >> tree()[0]
        nodes.findAll() >> tree()

        when:
        service.addMember(1L, 'keith.ji')

        then:
        1 * memberships.add(1L, 'keith.ji')
        1 * memberships.orgsOf('keith.ji') >> ([1L] as Set)
        1 * leaves.replaceAll('keith.ji', [3L, 4L] as Set)
    }

    def 'multiple membership paths to the same leaf are deduplicated after revocation'() {
        given:
        nodes.findAll() >> tree()

        when:
        service.removeMember(2L, 'keith.ji')

        then:
        1 * memberships.remove(2L, 'keith.ji')
        1 * memberships.orgsOf('keith.ji') >> ([3L, 1L] as Set)
        1 * leaves.replaceAll('keith.ji', [3L, 4L] as Set)
    }

    def 'removing the last membership revokes every leaf immediately'() {
        when:
        service.removeMember(3L, 'keith.ji')

        then:
        1 * memberships.remove(3L, 'keith.ji')
        1 * memberships.orgsOf('keith.ji') >> ([] as Set)
        1 * leaves.replaceAll('keith.ji', [] as Set)
    }

    def 'turning a former leaf into a parent moves its effective permission to the new leaf'() {
        given:
        nodes.findAll() >> (tree() + new OrgNode(5L, '支付小组', 3L))

        when:
        service.recompute('keith.ji')

        then:
        1 * memberships.orgsOf('keith.ji') >> ([3L] as Set)
        1 * leaves.replaceAll('keith.ji', [5L] as Set)
    }

    def 'direct member counts and effective leaf membership use distinct repositories'() {
        when:
        def direct = service.effectiveMembers(3L)
        def effective = service.isEffectiveMember('keith.ji', 3L)

        then:
        1 * memberships.membersOf(3L) >> (['alice'] as Set)
        1 * leaves.leavesOf('keith.ji') >> ([3L] as Set)
        direct == ['alice'] as Set
        effective
    }

    def 'missing organization is rejected before any membership write'() {
        given:
        nodes.findById(9999L) >> null

        when:
        service.addMember(9999L, 'keith.ji')

        then:
        0 * memberships.add(_, _)
        def error = thrown(NeocatException)
        error.code() == ORG_NOT_FOUND
    }

    def 'unknown account is rejected before any membership write'() {
        given:
        nodes.findById(3L) >> tree()[2]
        accounts.exists('missing') >> false

        when:
        service.addMember(3L, 'missing')

        then:
        def error = thrown(NeocatException)
        0 * memberships.add(_, _)
        error.code() == USER_NOT_FOUND
    }

    def 'only truly lost effective leaves emit synchronous revocation events'() {
        given:
        def publisher = Mock(ApplicationEventPublisher)
        def service = new OrgMembershipService(nodes, memberships, leaves, publisher, accounts)
        nodes.findAll() >> tree()

        when:
        service.removeMember(2L, 'keith.ji')

        then:
        1 * memberships.remove(2L, 'keith.ji')
        1 * leaves.leavesOf('keith.ji') >> ([3L, 4L] as Set)
        1 * memberships.orgsOf('keith.ji') >> ([3L] as Set)
        1 * leaves.replaceAll('keith.ji', [3L] as Set)
        1 * publisher.publishEvent(new EffectiveMembershipChanged('keith.ji', 4L, false))
        0 * publisher.publishEvent(new EffectiveMembershipChanged('keith.ji', 3L, false))
    }
}
