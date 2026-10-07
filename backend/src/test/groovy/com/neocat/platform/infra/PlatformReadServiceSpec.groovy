package com.neocat.platform.infra

import com.neocat.platform.domain.channel.ChannelConfig
import com.neocat.platform.domain.channel.ChannelConfigRepository
import com.neocat.platform.domain.channel.ChannelType
import com.neocat.platform.domain.profile.PlatformProfile
import com.neocat.platform.domain.profile.PlatformProfileRepository
import com.neocat.platform.domain.profile.SlowThresholds
import spock.lang.Specification

class PlatformReadServiceSpec extends Specification {
    def 'before first initialization the analysis thresholds use platform domain defaults'() {
        given:
        def profiles = Stub(PlatformProfileRepository) {
            load() >> null
        }
        def service = new PlatformReadService(profiles, Stub(ChannelConfigRepository))

        expect:
        service.slowThresholds().getUrlMs() == SlowThresholds.defaults().getUrlMs()
        service.slowThresholds().getSqlMs() == SlowThresholds.defaults().getSqlMs()
    }

    def 'current persisted thresholds override initialization defaults'() {
        given:
        def profiles = Stub(PlatformProfileRepository) {
            load() >> PlatformProfile.notInitialized().withSlowThresholds(
                    new SlowThresholds(300, 50, 400, 20))
        }

        expect:
        new PlatformReadService(profiles, Stub(ChannelConfigRepository)).slowThresholds().getCallMs() == 400
    }

    def 'channel selection reads enabled status from persisted configurations'() {
        given:
        def channels = Stub(ChannelConfigRepository) {
            findAll() >> [new ChannelConfig(ChannelType.EMAIL, true, 'smtp'),
                          new ChannelConfig(ChannelType.FEISHU, false, null)]
        }
        def service = new PlatformReadService(Stub(PlatformProfileRepository), channels)

        expect:
        service.channelEnabled('EMAIL')
        !service.channelEnabled('FEISHU')
        !service.channelEnabled('DINGTALK')
    }

    def 'enabled flag without any actual channel configuration cannot be selected'() {
        given:
        def channels = Stub(ChannelConfigRepository) {
            findAll() >> [new ChannelConfig(ChannelType.EMAIL, true, null)]
        }

        expect:
        !new PlatformReadService(Stub(PlatformProfileRepository), channels).channelEnabled('EMAIL')
    }
}
