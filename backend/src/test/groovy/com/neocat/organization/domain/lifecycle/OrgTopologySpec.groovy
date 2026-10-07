package com.neocat.organization.domain.lifecycle

import com.neocat.organization.domain.membership.EffectiveLeafRepository
import com.neocat.organization.domain.membership.MembershipRepository
import com.neocat.organization.domain.tree.DeletionPreview
import com.neocat.organization.domain.tree.OrgNode
import com.neocat.organization.domain.tree.OrgNodeRepository

import com.neocat.common.error.NeocatException
import spock.lang.Specification

import static com.neocat.common.error.ErrorCode.*

class OrgTopologySpec extends Specification {
    OrgNodeRepository nodes = Mock()
    OrgResourceGateway resources = Mock()
    EffectiveLeafRepository leaves = Mock()
    MembershipRepository memberships = Mock()
    OrgLifecycleService service = new OrgLifecycleService(nodes, resources, memberships, leaves)

    static OrgNode leaf() { new OrgNode(7L, '支付组', 1L) }

    def 'adding a child to a leaf with dashboards is forbidden'() {
        when:
        service.addChildNode(7L, '支付小组')

        then:
        1 * nodes.findById(7L) >> leaf()
        1 * nodes.childrenOf(7L) >> []
        1 * resources.hasDashboards(7L) >> true
        0 * nodes.create(_, _)
        def error = thrown(NeocatException)
        error.code() == LEAF_HAS_RESOURCES
    }

    def 'adding a child to a leaf with organization alerts is forbidden'() {
        when:
        service.addChildNode(7L, '支付小组')

        then:
        1 * nodes.findById(7L) >> leaf()
        1 * nodes.childrenOf(7L) >> []
        1 * resources.hasDashboards(7L) >> false
        1 * resources.hasAlertRules(7L) >> true
        0 * nodes.create(_, _)
        def error = thrown(NeocatException)
        error.code() == LEAF_HAS_RESOURCES
    }

    def 'a resource-free leaf accepts a child'() {
        when:
        def child = service.addChildNode(7L, '支付小组')

        then:
        1 * nodes.findById(7L) >> leaf()
        1 * nodes.childrenOf(7L) >> []
        1 * resources.hasDashboards(7L) >> false
        1 * resources.hasAlertRules(7L) >> false
        1 * nodes.create('支付小组', 7L) >> new OrgNode(8L, '支付小组', 7L)
        child.getParentId() == 7L
    }

    def 'nonleaf parent does not check its resource projection to add a child'() {
        when:
        service.addChildNode(1L, '风控组')

        then:
        1 * nodes.findById(1L) >> new OrgNode(1L, '总部', null)
        1 * nodes.childrenOf(1L) >> [leaf()]
        0 * resources.hasDashboards(_)
        0 * resources.hasAlertRules(_)
        1 * nodes.create('风控组', 1L) >> new OrgNode(9L, '风控组', 1L)
    }

    def 'a nonleaf cannot be deleted'() {
        when:
        service.deleteNode(1L, '总部')

        then:
        1 * nodes.findById(1L) >> new OrgNode(1L, '总部', null)
        1 * nodes.childrenOf(1L) >> [leaf()]
        0 * resources.deleteAllOf(_)
        def error = thrown(NeocatException)
        error.code() == HAS_CHILDREN
    }

    def 'deletion preview contains cards, rules and inherited effective members'() {
        when:
        def preview = service.previewDeletion(7L)

        then:
        1 * nodes.findById(7L) >> leaf()
        1 * resources.dashboardsOf(7L) >> [new DeletionPreview.DashboardSummary(12L, '订单', 3L)]
        1 * resources.alertRuleCount(7L) >> 2L
        1 * leaves.membersOf(7L) >> ([100L, 200L] as Set)
        preview.dashboardCount() == 1L
        preview.cardCount() == 3L
        preview.getAlertRuleCount() == 2L
        preview.getMemberCount() == 2L
    }

    def 'confirmation name mismatch never deletes resources or accounts'() {
        when:
        service.deleteNode(7L, '错误名称')

        then:
        1 * nodes.findById(7L) >> leaf()
        1 * nodes.childrenOf(7L) >> []
        0 * resources.deleteAllOf(_)
        0 * nodes.delete(_)
        def error = thrown(NeocatException)
        error.code() == CONFIRM_NAME_MISMATCH
    }

    def 'confirmed deletion requests synchronous resource cascade before deleting the leaf'() {
        when:
        service.deleteNode(7L, '支付组')

        then:
        1 * nodes.findById(7L) >> leaf()
        1 * nodes.childrenOf(7L) >> []
        1 * leaves.membersOf(7L) >> ([] as Set)
        1 * memberships.membersOf(7L) >> ([] as Set)
        1 * resources.deleteAllOf(7L)
        1 * leaves.removeOrg(7L)
        1 * nodes.delete(7L)
    }

    def 'an unavailable cascade stops node deletion instead of orphaning resources'() {
        when:
        service.deleteNode(7L, '支付组')

        then:
        1 * nodes.findById(7L) >> leaf()
        1 * nodes.childrenOf(7L) >> []
        1 * leaves.membersOf(7L) >> ([] as Set)
        1 * memberships.membersOf(7L) >> ([] as Set)
        1 * resources.deleteAllOf(7L) >> { throw new IllegalStateException('cascade failed') }
        0 * nodes.delete(_)
        thrown(IllegalStateException)
    }

    def 'missing node is rejected before any deletion side effects'() {
        when:
        service.deleteNode(9999L, '任意')

        then:
        1 * nodes.findById(9999L) >> null
        0 * resources.deleteAllOf(_)
        def error = thrown(NeocatException)
        error.code() == ORG_NOT_FOUND
    }
}
