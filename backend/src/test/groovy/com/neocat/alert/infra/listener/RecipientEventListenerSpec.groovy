package com.neocat.alert.infra.listener

import com.neocat.alert.domain.recipient.RecipientService
import com.neocat.identity.api.internal.AccountStatusChanged
import com.neocat.organization.api.internal.EffectiveMembershipChanged
import spock.lang.Specification
import com.neocat.alert.domain.recipient.OrgMembershipChanged
import com.neocat.alert.domain.recipient.UserDisabled
import com.neocat.alert.domain.recipient.UserEnabled

class RecipientEventListenerSpec extends Specification {
    def 'disabling an account removes recipients but re-enabling never restores them'() {
        given:
        def recipients = Mock(RecipientService)
        def listener = new RecipientEventListener(recipients)

        when:
        listener.on(new AccountStatusChanged(100L, false))
        listener.on(new AccountStatusChanged(100L, true))

        then:
        1 * recipients.onEvent(new UserDisabled(100L))
        1 * recipients.onEvent(new UserEnabled(100L))
    }

    def 'effective membership revocation is forwarded with account and leaf IDs'() {
        given:
        def recipients = Mock(RecipientService)

        when:
        new RecipientEventListener(recipients).on(new EffectiveMembershipChanged(100L, 7L, false))

        then:
        1 * recipients.onEvent(new OrgMembershipChanged(100L, 7L, false))
    }
}
