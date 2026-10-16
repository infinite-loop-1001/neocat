package com.neocat.identity.infra.adapter

import com.neocat.identity.domain.account.Account
import com.neocat.identity.domain.account.AccountRepository
import com.neocat.identity.domain.account.AccountStatus
import com.neocat.identity.domain.auth.PasswordHasher
import com.neocat.identity.domain.account.Role
import com.neocat.identity.api.internal.AccountDirectory
import spock.lang.Specification

import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import com.neocat.common.time.clock.TimeProvider

class SuperAdminProvisioningAdapterSpec extends Specification {
    def cleanup() {
        TimeProvider.clock = Clock.systemUTC()
    }

    static final Instant NOW = Instant.parse('2026-09-24T04:00:00Z')

    def 'first super admin is persisted with hashed password and forced change'() {
        given:
        def directory = Mock(AccountDirectory)
        def adapter = new SuperAdminProvisioningAdapter(directory)

        when:
        def created = adapter.createSuperAdmin('root', 'secret123')

        then:
        1 * directory.createInitialSuperAdmin('root', 'secret123') >> 'root'
        created == 'root'
    }

    def 'identity creates the first super admin with hashed password and forced change'() {
        given:
        def accounts = Mock(AccountRepository)
        def passwords = Mock(PasswordHasher)
        TimeProvider.clock = Clock.fixed(NOW, ZoneOffset.UTC)
        def directory = new AccountDirectoryService(accounts, passwords)

        when:
        def created = directory.createInitialSuperAdmin('root', 'secret123')

        then:
        1 * accounts.findByUserAccount('root') >> null
        1 * passwords.hash('secret123') >> 'bcrypt-hash'
        1 * accounts.create('root', 'bcrypt-hash', Role.SUPER_ADMIN, true, NOW) >>
                new Account(7L, 'root', 'bcrypt-hash', Role.SUPER_ADMIN, AccountStatus.ENABLED, true, NOW)
        created == 'root'
    }

    def 'an existing username cannot be replaced by initialization'() {
        given:
        def accounts = Mock(AccountDirectory)
        def adapter = new SuperAdminProvisioningAdapter(accounts)

        when:
        adapter.createSuperAdmin('root', 'secret123')

        then:
        1 * accounts.createInitialSuperAdmin('root', 'secret123') >> {
            throw new IllegalStateException('user account already exists')
        }
        thrown(IllegalStateException)
    }
}
