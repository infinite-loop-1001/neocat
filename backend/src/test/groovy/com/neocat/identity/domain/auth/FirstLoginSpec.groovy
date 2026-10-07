package com.neocat.identity.domain.auth

import com.neocat.identity.domain.account.Account
import com.neocat.identity.domain.account.AccountRepository
import com.neocat.identity.domain.account.AccountService
import com.neocat.identity.domain.account.AccountStatus
import com.neocat.identity.domain.account.Role
import com.neocat.identity.domain.session.AccessHistoryRepository
import com.neocat.identity.domain.session.Session
import com.neocat.identity.domain.session.SessionRepository

import spock.lang.Specification

import java.time.Instant

import static com.neocat.common.error.ErrorCode.PASSWORD_CHANGE_REQUIRED

class FirstLoginSpec extends Specification {
    static final Instant NOW = Instant.parse('2026-09-24T04:00:00Z')

    AccountRepository accounts = Mock()
    SessionRepository sessions = Mock()
    PasswordHasher passwords = Mock()
    AccessHistoryRepository history = Mock()
    ServiceAvailability availability = Mock()
    AccountService service = new AccountService(accounts, sessions, passwords, history)

    static Account account(boolean mustChange) {
        new Account(7L, 'alice', 'hash', Role.USER, AccountStatus.ENABLED, mustChange, NOW)
    }

    def 'temporary session only permits password change, logout and self lookup'() {
        given:
        def sessions = Stub(SessionRepository) {
            isValid('sid', NOW) >> true
            findSession('sid') >> new Session('sid', 7L, NOW.plusSeconds(1800))
        }
        def accounts = Stub(AccountRepository) {
            findById(7L) >> account(true)
        }
        def guard = new SessionGuard(accounts, sessions)

        expect:
        guard.check('sid', 'POST /api/me/password', NOW) == null
        guard.check('sid', 'POST /api/logout', NOW) == null
        guard.check('sid', 'GET /api/me', NOW) == null
        guard.check('sid', 'GET /api/services', NOW)?.code() == PASSWORD_CHANGE_REQUIRED
        guard.check('sid', 'GET /api/dashboards?orgId=1', NOW)?.code() == PASSWORD_CHANGE_REQUIRED
    }

    def 'a recent service with actual data becomes the login target'() {
        when:
        def target = service.resolveLoginTarget(7L, availability)

        then:
        1 * history.recentServices(7L, 10) >> ['order']
        1 * availability.hasData('order') >> true
        target.getTargetService() == 'order'
    }

    def 'without recent history or service data the login target is the service list'() {
        when:
        def target = service.resolveLoginTarget(7L, availability)

        then:
        1 * history.recentServices(7L, 10) >> ['order']
        1 * availability.hasData('order') >> false
        target.isServiceList()
    }

    def 'among recent services the first with data wins'() {
        when:
        def target = service.resolveLoginTarget(7L, availability)

        then:
        1 * history.recentServices(7L, 10) >> ['order', 'pay']
        1 * availability.hasData('order') >> false
        1 * availability.hasData('pay') >> true
        target.getTargetService() == 'pay'
    }

    def 'a service visit is persisted for next login, not cached in the service'() {
        when:
        service.recordAccess(7L, 'order', NOW)

        then:
        1 * history.record(7L, 'order', NOW)
    }

    def 'successful first password change removes the forced-change flag'() {
        when:
        def updated = service.changePassword(7L, 'oldpass12', 'newpass12', NOW)

        then:
        1 * accounts.findById(7L) >> account(true)
        1 * passwords.matches('oldpass12', 'hash') >> true
        1 * passwords.hash('newpass12') >> 'new-hash'
        1 * accounts.save({ !it.isMustChangePassword() && it.getPasswordHash() == 'new-hash' }) >>
                { args -> args[0] }
        !updated.isMustChangePassword()
    }
}
