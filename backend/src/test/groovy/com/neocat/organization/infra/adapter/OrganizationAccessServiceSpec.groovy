package com.neocat.organization.infra.adapter

import com.neocat.organization.domain.membership.EffectiveLeafRepository
import com.neocat.organization.domain.tree.OrgNode
import com.neocat.organization.domain.tree.OrgNodeRepository
import spock.lang.Specification

class OrganizationAccessServiceSpec extends Specification {
    def 'nonexistent organization is not considered a leaf'() {
        given:
        def nodes = Mock(OrgNodeRepository)
        def service = new OrganizationAccessService(nodes, Stub(EffectiveLeafRepository))

        when:
        def leaf = service.isLeaf(7L)

        then:
        1 * nodes.findById(7L) >> null
        0 * nodes.childrenOf(_)
        !leaf
    }

    def 'leaf access comes from effective leaf projection, never direct membership'() {
        given:
        def leaves = Mock(EffectiveLeafRepository)
        def service = new OrganizationAccessService(Stub(OrgNodeRepository), leaves)

        when:
        def allowed = service.isEffectiveMember(12L, 3L)

        then:
        1 * leaves.leavesOf(12L) >> ([3L] as Set)
        allowed
    }
}
