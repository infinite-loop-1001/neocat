package com.neocat.organization.domain.tree

import com.neocat.organization.domain.lifecycle.OrgResourceGateway
import com.neocat.organization.domain.membership.OrgMembershipService

import com.neocat.common.error.NeocatException
import spock.lang.Specification

import static com.neocat.common.error.ErrorCode.*

class OrgTreeSpec extends Specification {
    OrgNodeRepository nodes = Mock()
    OrgResourceGateway resources = Mock()
    OrgTreeService service = new OrgTreeService(nodes, resources)

    def 'a new root delegates ID allocation to the production repository'() {
        when:
        def created = service.createNode('总部', null)

        then:
        1 * nodes.findAll() >> []
        1 * nodes.create('总部', null) >> new OrgNode(1L, '总部', null)
        created.isRoot()
    }

    def 'a missing parent is rejected without writing a node'() {
        when:
        service.createNode('支付组', 9999L)

        then:
        1 * nodes.findById(9999L) >> null
        0 * nodes.create(_, _)
        def e = thrown(NeocatException)
        e.code() == PARENT_ORG_NOT_FOUND
    }

    def 'same-parent duplicate names are rejected'() {
        when:
        service.createNode('支付组', 1L)

        then:
        1 * nodes.findById(1L) >> new OrgNode(1L, '总部', null)
        1 * nodes.findAll() >> [new OrgNode(2L, '支付组', 1L)]
        0 * nodes.create(_, _)
        def e = thrown(NeocatException)
        e.code() == NAME_DUPLICATED
    }

    def 'the same name is allowed under different parents'() {
        when:
        def result = service.createNode('支付组', 3L)

        then:
        1 * nodes.findById(3L) >> new OrgNode(3L, '分公司', null)
        1 * nodes.findAll() >> [new OrgNode(2L, '支付组', 1L)]
        1 * nodes.childrenOf(3L) >> [new OrgNode(5L, '销售组', 3L)]
        1 * nodes.create('支付组', 3L) >> new OrgNode(4L, '支付组', 3L)
        result.getParentId() == 3L
    }

    def 'HTTP creation path cannot turn a leaf with resources into a parent'() {
        when:
        service.createNode('支付小组', 7L)

        then:
        1 * nodes.findById(7L) >> new OrgNode(7L, '支付组', null)
        1 * nodes.findAll() >> [new OrgNode(7L, '支付组', null)]
        1 * nodes.childrenOf(7L) >> []
        1 * resources.hasDashboards(7L) >> true
        0 * nodes.create(_, _)
        def error = thrown(NeocatException)
        error.code() == LEAF_HAS_RESOURCES
    }

    def 'creating a child recomputes inherited effective permissions'() {
        given:
        def memberships = Mock(OrgMembershipService)
        def service = new OrgTreeService(nodes, resources, memberships)

        when:
        service.createNode('支付小组', 7L)

        then:
        1 * nodes.findById(7L) >> new OrgNode(7L, '支付组', null)
        1 * nodes.findAll() >> [new OrgNode(7L, '支付组', null)]
        1 * nodes.childrenOf(7L) >> []
        1 * resources.hasDashboards(7L) >> false
        1 * resources.hasAlertRules(7L) >> false
        1 * nodes.create('支付小组', 7L) >> new OrgNode(8L, '支付小组', 7L)
        1 * memberships.recomputeAll()
    }

    def 'leaf status is derived from repository children'() {
        when:
        def leaf = service.isLeaf(7L)

        then:
        1 * nodes.findById(7L) >> new OrgNode(7L, '支付组', 1L)
        1 * nodes.childrenOf(7L) >> []
        leaf
    }

    def 'rename checks uniqueness under the existing parent before saving'() {
        when:
        def renamed = service.rename(7L, '支付中心')

        then:
        1 * nodes.findById(7L) >> new OrgNode(7L, '支付组', 1L)
        1 * nodes.findAll() >> [new OrgNode(7L, '支付组', 1L)]
        1 * nodes.save(new OrgNode(7L, '支付中心', 1L)) >>
                new OrgNode(7L, '支付中心', 1L)
        renamed.getName() == '支付中心'
    }

    def 'missing rename target is rejected'() {
        when:
        service.rename(9999L, '任意')

        then:
        1 * nodes.findById(9999L) >> null
        def e = thrown(NeocatException)
        e.code() == ORG_NOT_FOUND
    }
}
