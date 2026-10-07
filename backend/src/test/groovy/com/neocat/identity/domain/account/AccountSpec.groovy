package com.neocat.identity.domain.account

import com.neocat.identity.domain.auth.AuthenticationService
import com.neocat.identity.domain.auth.PasswordHasher
import com.neocat.identity.domain.session.AccessHistoryRepository
import com.neocat.identity.domain.session.Session
import com.neocat.identity.domain.session.SessionRepository

import com.neocat.common.error.NeocatException
import spock.lang.Specification
import spock.lang.Unroll
import com.neocat.identity.api.internal.AccountStatusChanged
import org.springframework.context.ApplicationEventPublisher

import java.time.Instant

import static com.neocat.common.error.ErrorCode.*

class AccountSpec extends Specification {
    static final Instant NOW = Instant.parse('2026-09-24T04:00:00Z')

    AccountRepository accounts = Mock()
    SessionRepository sessions = Mock()
    PasswordHasher hasher = Mock()
    AccessHistoryRepository history = Mock()
    AccountService service = new AccountService(accounts, sessions, hasher, history)

    static Account alice(boolean firstLogin = true) {
        new Account(7L, 'alice', 'hash', Role.USER, AccountStatus.ENABLED, firstLogin, NOW)
    }

    @Unroll
    def 'password shorter than eight characters is rejected before writing: #password'() {
        when:
        service.create('alice', password, NOW)

        then:
        0 * accounts.create(_, _, _, _, _)
        def error = thrown(NeocatException)
        error.code() == PASSWORD_TOO_SHORT

        where:
        password << ['', '1', '1234567']
    }

    def 'creating an account hashes its password and marks initial password change'() {
        when:
        def created = service.create('alice', '12345678', NOW)

        then:
        1 * accounts.findByUsername('alice') >> null
        1 * hasher.hash('12345678') >> 'hash'
        1 * accounts.create('alice', 'hash', Role.USER, true, NOW) >> alice()
        created.isMustChangePassword()
        created.getRole() == Role.USER
    }

    def 'duplicate username is rejected before hashing the candidate password'() {
        when:
        service.create('alice', '12345678', NOW)

        then:
        1 * accounts.findByUsername('alice') >> alice()
        0 * hasher.hash(_)
        0 * accounts.create(_, _, _, _, _)
        def error = thrown(NeocatException)
        error.code() == USER_EXISTS
    }

    @Unroll
    def 'administrator cannot directly create #role'() {
        when:
        service.createAsAdmin('new', '12345678', role, NOW)

        then:
        0 * accounts.create(_, _, _, _, _)
        def error = thrown(NeocatException)
        error.code() == FORBIDDEN

        where:
        role << [Role.ADMIN, Role.SUPER_ADMIN]
    }

    def 'only super admin can grant admin, and cannot modify their own role'() {
        when:
        service.changeRole(7L, Role.ADMIN, Role.ADMIN)

        then:
        0 * accounts.save(_)
        def forbidden = thrown(NeocatException)
        forbidden.code() == FORBIDDEN

        when:
        service.changeRole(7L, Role.ADMIN, Role.SUPER_ADMIN, 7L)

        then:
        0 * accounts.save(_)
        def selfChange = thrown(NeocatException)
        selfChange.code() == CANNOT_MODIFY_SELF
    }

    def 'super admin can promote and demote another account'() {
        when:
        def promoted = service.changeRole(7L, Role.ADMIN, Role.SUPER_ADMIN, 1L)

        then:
        1 * accounts.findById(7L) >> alice()
        1 * accounts.save({ it.getRole() == Role.ADMIN }) >> { args -> args[0] }
        promoted.getRole() == Role.ADMIN
    }

    def 'reset password forces change and invalidates all sessions'() {
        when:
        def changed = service.resetPassword(7L, 'newpass123', NOW)

        then:
        1 * accounts.findById(7L) >> alice(false)
        1 * hasher.hash('newpass123') >> 'new-hash'
        1 * accounts.save({ it.isMustChangePassword() && it.getPasswordHash() == 'new-hash' }) >>
                { args -> args[0] }
        1 * sessions.invalidateAllOf(7L)
        changed.isMustChangePassword()
    }

    def 'disable revokes sessions and enable retains membership without restoring recipients'() {
        when:
        def disabled = service.disable(7L, NOW)

        then:
        1 * accounts.findById(7L) >> alice()
        1 * accounts.save({ it.getStatus() == AccountStatus.DISABLED }) >> { args -> args[0] }
        1 * sessions.invalidateAllOf(7L)
        disabled.isPreservedMemberships()
        disabled.status() == AccountStatus.DISABLED

        when:
        def enabled = service.enable(7L, NOW)

        then:
        1 * accounts.findById(7L) >> alice().withStatus(AccountStatus.DISABLED)
        1 * accounts.save({ it.getStatus() == AccountStatus.ENABLED }) >> { args -> args[0] }
        0 * sessions.invalidateAllOf(_)
        !enabled.isRecipientsRestored()
    }

    def 'account status event is published synchronously after session revocation'() {
        given:
        def publisher = Mock(ApplicationEventPublisher)
        def service = new AccountService(accounts, sessions, hasher, history, publisher)

        when:
        service.disable(7L, NOW)

        then:
        1 * accounts.findById(7L) >> alice()
        1 * accounts.save({ it.getStatus() == AccountStatus.DISABLED }) >> { args -> args[0] }
        1 * sessions.invalidateAllOf(7L)
        1 * publisher.publishEvent(new AccountStatusChanged(7L, false))
    }

    def 'wrong old password, same new password and short new password fail separately'() {
        when:
        service.changePassword(7L, 'wrong', 'newpass123', NOW)

        then:
        1 * accounts.findById(7L) >> alice()
        1 * hasher.matches('wrong', 'hash') >> false
        def wrongOld = thrown(NeocatException)
        wrongOld.code() == BAD_CREDENTIALS

        when:
        service.changePassword(7L, '12345678', '12345678', NOW)

        then:
        1 * accounts.findById(7L) >> alice()
        1 * hasher.matches('12345678', 'hash') >> true
        def unchanged = thrown(NeocatException)
        unchanged.code() == PASSWORD_UNCHANGED

        when:
        service.changePassword(7L, '12345678', 'short', NOW)

        then:
        1 * accounts.findById(7L) >> alice()
        1 * hasher.matches('12345678', 'hash') >> true
        def tooShort = thrown(NeocatException)
        tooShort.code() == PASSWORD_TOO_SHORT
    }

    def 'successful password change clears the first-login flag'() {
        when:
        def changed = service.changePassword(7L, '12345678', 'newpass123', NOW)

        then:
        1 * accounts.findById(7L) >> alice()
        1 * hasher.matches('12345678', 'hash') >> true
        1 * hasher.hash('newpass123') >> 'new-hash'
        1 * accounts.save({ !it.isMustChangePassword() && it.getPasswordHash() == 'new-hash' }) >>
                { args -> args[0] }
        !changed.isMustChangePassword()
    }

    def 'missing account on password reset uses user-not-found error'() {
        when:
        service.resetPassword(9999L, 'newpass123', NOW)

        then:
        1 * accounts.findById(9999L) >> null
        def missing = thrown(NeocatException)
        missing.code() == USER_NOT_FOUND
    }

    def 'authentication rejects missing, disabled and wrong-password accounts identically'() {
        given:
        def auth = new AuthenticationService(accounts, sessions, hasher)

        when:
        auth.login('ghost', '12345678', NOW)

        then:
        1 * accounts.findByUsername('ghost') >> null
        def unknown = thrown(NeocatException)
        unknown.code() == BAD_CREDENTIALS

        when:
        auth.login('alice', '12345678', NOW)

        then:
        1 * accounts.findByUsername('alice') >> alice().withStatus(AccountStatus.DISABLED)
        def disabled = thrown(NeocatException)
        disabled.code() == BAD_CREDENTIALS

        when:
        auth.login('alice', 'wrongpass', NOW)

        then:
        1 * accounts.findByUsername('alice') >> alice()
        1 * hasher.matches('wrongpass', 'hash') >> false
        def invalid = thrown(NeocatException)
        invalid.code() == BAD_CREDENTIALS
    }

    def 'successful login creates session and returns the password-change requirement'() {
        given:
        def auth = new AuthenticationService(accounts, sessions, hasher)
        def session = new Session('sid', 7L, NOW.plusSeconds(1800))

        when:
        def result = auth.login('alice', '12345678', NOW)

        then:
        1 * accounts.findByUsername('alice') >> alice()
        1 * hasher.matches('12345678', 'hash') >> true
        1 * sessions.create(7L, NOW) >> session
        result.getSession() == session
        result.isMustChangePassword()
    }
}
