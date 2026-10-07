package com.neocat.organization.infra.jdbc

import spock.lang.Specification

class OrganizationRepositoryAdapterSpec extends Specification {
    def '添加成员委托幂等插入，移除仅指定账号与组织'() {
        given:
        def mapper = Mock(MembershipMapper)
        def repository = new MembershipRepositoryAdapter(mapper)

        when:
        repository.add(3L, 7L)
        repository.remove(3L, 7L)
        repository.removeAllOf(7L)

        then:
        1 * mapper.add(3L, 7L)
        1 * mapper.remove(3L, 7L)
        1 * mapper.removeAllOf(7L)
        0 * mapper._
    }

    def '查询成员和直接加入的组织以 Set 返回'() {
        given:
        def mapper = Stub(MembershipMapper) {
            membersOf(3L) >> [7L, 8L]
            orgsOf(7L) >> [3L, 4L]
        }
        def repository = new MembershipRepositoryAdapter(mapper)

        expect:
        repository.membersOf(3L) == [7L, 8L] as Set
        repository.orgsOf(7L) == [3L, 4L] as Set
    }

    def '有效叶子替换先删除旧集合再逐个插入；空集合只删除'() {
        given:
        def mapper = Mock(EffectiveLeafMapper)
        def repository = new EffectiveLeafRepositoryAdapter(mapper)

        when:
        repository.replaceAll(7L, [3L, 4L] as Set)
        repository.replaceAll(7L, [] as Set)

        then:
        1 * mapper.deleteByAccount(7L)
        1 * mapper.insert(7L, 3L)
        1 * mapper.insert(7L, 4L)
        1 * mapper.deleteByAccount(7L)
        0 * mapper._
    }

    def '权限与反向成员查询来自 mapper'() {
        given:
        def mapper = Stub(EffectiveLeafMapper) {
            leavesOf(7L) >> [3L, 4L]
            membersOf(3L) >> [7L]
        }
        def repository = new EffectiveLeafRepositoryAdapter(mapper)

        expect:
        repository.leavesOf(7L) == [3L, 4L] as Set
        repository.membersOf(3L) == [7L] as Set
    }
}
