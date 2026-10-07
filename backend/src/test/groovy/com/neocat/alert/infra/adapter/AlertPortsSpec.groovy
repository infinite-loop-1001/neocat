package com.neocat.alert.infra.adapter

import com.neocat.alert.domain.rule.AlertChannel
import com.neocat.alert.domain.engine.AlertNotification
import com.neocat.identity.api.internal.AccountDirectory
import com.neocat.organization.api.internal.OrganizationAccess
import com.neocat.platform.api.internal.PlatformReadModel
import spock.lang.Specification

class AlertPortsSpec extends Specification {
    def 'recipient checks account status and effective organization access separately'() {
        given:
        def accounts = Mock(AccountDirectory)
        def organizations = Mock(OrganizationAccess)
        def gateway = new RecipientEligibilityAdapter(accounts, organizations)

        when:
        def enabled = gateway.isEnabled(12L)
        def member = gateway.isEffectiveMember(12L, 3L)

        then:
        1 * accounts.enabled(12L) >> true
        1 * organizations.isEffectiveMember(12L, 3L) >> false
        enabled
        !member
    }

    def 'channel availability reflects current platform settings'() {
        given:
        def platform = Mock(PlatformReadModel)

        when:
        def available = new ConfiguredChannelAvailability(platform).available(AlertChannel.EMAIL)

        then:
        1 * platform.channelEnabled('EMAIL') >> true
        available
    }

    def 'deferred external delivery fails visibly instead of reporting success'() {
        when:
        new UnsupportedExternalNotifier().send(new AlertNotification(
                1L, 'rule', [7L], AlertChannel.EMAIL, 'message', 123L))

        then:
        thrown(UnsupportedOperationException)
    }
}
