package com.neocat.identity.infra.jdbc

import com.neocat.identity.domain.session.Session
import spock.lang.Specification

import java.sql.Timestamp
import java.time.Instant

class SessionRepositoryAdapterSpec extends Specification {
    static final Instant NOW = Instant.parse('2026-09-24T04:00:00Z')

    SessionMapper mapper = Mock()
    SessionRepositoryAdapter repository = new SessionRepositoryAdapter(mapper)

    def '创建会话生成唯一 ID 并写入三十分钟过期时间'() {
        when:
        def first = repository.create(1L, NOW)
        def second = repository.create(1L, NOW)

        then:
        first.getId() != second.getId()
        first.getExpiresAt() == NOW.plusSeconds(Session.SLIDING_SECONDS)
        2 * mapper.insert(_ as String, 1L, Timestamp.from(NOW),
                Timestamp.from(NOW.plusSeconds(Session.SLIDING_SECONDS))) >> 1
    }

    def '续期通过条件更新防止过期会话复活'() {
        when:
        repository.touch('sid', NOW)

        then:
        1 * mapper.touchIfValid('sid', Timestamp.from(NOW),
                Timestamp.from(NOW.plusSeconds(Session.SLIDING_SECONDS))) >> 1
    }

    def '会话在过期边界不可用'() {
        given:
        def row = new SessionRepositoryAdapter.SessionRow()
        row.id = 'sid'
        row.accountId = 1L
        row.expiresAt = Timestamp.from(NOW)

        when:
        def before = repository.isValid('sid', NOW.minusMillis(1))
        def boundary = repository.isValid('sid', NOW)
        def session = repository.findSession('sid')

        then:
        3 * mapper.selectById('sid') >> row
        before
        !boundary
        session == new Session('sid', 1L, NOW)
    }

    def '失效仅删除指定会话，账号级撤销删除全部会话'() {
        when:
        repository.invalidate('sid')
        repository.invalidateAllOf(1L)

        then:
        1 * mapper.deleteById('sid') >> 1
        1 * mapper.deleteByAccountId(1L) >> 2
    }
}
