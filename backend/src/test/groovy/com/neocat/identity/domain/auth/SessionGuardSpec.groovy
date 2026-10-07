package com.neocat.identity.domain.auth

import com.neocat.identity.domain.account.Account
import com.neocat.identity.domain.account.AccountRepository
import com.neocat.identity.domain.account.AccountStatus
import com.neocat.identity.domain.account.Role
import com.neocat.identity.domain.session.Session
import com.neocat.identity.domain.session.SessionRepository

import com.neocat.common.error.ErrorCode
import com.neocat.common.error.NeocatException
import spock.lang.Specification

import java.time.Instant

/** Domain rules only: persistence/expiry mechanics are tested by SessionRepositoryAdapterSpec. */
class SessionGuardSpec extends Specification {
    static final Instant NOW = Instant.parse('2026-09-24T04:00:00Z')

    AccountRepository accounts = Mock()
    SessionRepository sessions = Mock()
    SessionGuard guard = new SessionGuard(accounts, sessions)

    def 'missing session cannot access a protected endpoint'() {
        when:
        NeocatException error = guard.check(null, 'GET /api/report', NOW)

        then:
        error.code() == ErrorCode.UNAUTHENTICATED
        0 * sessions._
        0 * accounts._
    }

    def 'expired session cannot be renewed'() {
        when:
        NeocatException error = guard.check('sid', 'GET /api/report', NOW)

        then:
        1 * sessions.isValid('sid', NOW) >> false
        0 * sessions._
        error.code() == ErrorCode.UNAUTHENTICATED
    }

    def 'valid request slides the expiry once'() {
        given:
        def account = new Account(1L, 'alice', 'hash', Role.USER, AccountStatus.ENABLED, false, NOW)
        def session = new Session('sid', 1L, NOW.plusSeconds(1800))

        when:
        def error = guard.check('sid', 'GET /api/report', NOW)

        then:
        1 * sessions.isValid('sid', NOW) >> true
        1 * sessions.findSession('sid') >> session
        1 * accounts.findById(1L) >> account
        1 * sessions.touch('sid', NOW)
        error == null
    }

    def 'disabled account invalidates its session instead of renewing it'() {
        given:
        def account = new Account(1L, 'alice', 'hash', Role.USER, AccountStatus.DISABLED, false, NOW)

        when:
        NeocatException error = guard.check('sid', 'GET /api/report', NOW)

        then:
        1 * sessions.isValid('sid', NOW) >> true
        1 * sessions.findSession('sid') >> new Session('sid', 1L, NOW.plusSeconds(1800))
        1 * accounts.findById(1L) >> account
        1 * sessions.invalidate('sid')
        0 * sessions.touch(_, _)
        error.code() == ErrorCode.UNAUTHENTICATED
    }

    def 'forced password change blocks other endpoints without renewal'() {
        given:
        def account = new Account(1L, 'alice', 'hash', Role.USER, AccountStatus.ENABLED, true, NOW)

        when:
        NeocatException error = guard.check('sid', 'GET /api/report', NOW)

        then:
        1 * sessions.isValid('sid', NOW) >> true
        1 * sessions.findSession('sid') >> new Session('sid', 1L, NOW.plusSeconds(1800))
        1 * accounts.findById(1L) >> account
        0 * sessions.touch(_, _)
        error.code() == ErrorCode.PASSWORD_CHANGE_REQUIRED
    }
}
