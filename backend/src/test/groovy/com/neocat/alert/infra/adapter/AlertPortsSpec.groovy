package com.neocat.alert.infra.adapter

import com.neocat.identity.api.internal.AccountDirectory
import com.neocat.organization.api.internal.OrganizationAccess
import spock.lang.Specification

class AlertPortsSpec extends Specification {
    def 'recipient checks account status and effective organization access separately'() {
        given:
        def accounts = Mock(AccountDirectory)
        def organizations = Mock(OrganizationAccess)
        def gateway = new RecipientEligibilityAdapter(accounts, organizations)

        when:
        def enabled = gateway.isEnabled('keith.ji')
        def member = gateway.isEffectiveMember('keith.ji', 3L)

        then:
        1 * accounts.enabled('keith.ji') >> true
        1 * organizations.isEffectiveMember('keith.ji', 3L) >> false
        enabled
        !member
    }
}
