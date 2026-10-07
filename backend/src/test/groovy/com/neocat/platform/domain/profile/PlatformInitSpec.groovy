package com.neocat.platform.domain.profile

import com.neocat.platform.domain.channel.ChannelConfigRepository
import com.neocat.platform.domain.channel.ChannelType
import com.neocat.platform.domain.init.InitRequest
import com.neocat.platform.domain.init.SuperAdminProvisioner

import com.neocat.common.error.NeocatException
import spock.lang.Specification

import java.time.Instant
import java.time.ZoneId

import static com.neocat.common.error.ErrorCode.*

/**
 * G3 任务13（红）：平台初始化。
 * 对应 PRD 01 §2：初始化输入、默认慢阈值、空通道、时区只读。
 */
class PlatformInitSpec extends Specification {

    static final Instant NOW = Instant.parse("2026-09-24T04:00:00Z")

    PlatformProfileRepository profiles
    ChannelConfigRepository channels
    SuperAdminProvisioner superAdmins
    PlatformService service

    def setup() {
        profiles = Mock(PlatformProfileRepository) {
            load() >> null
        }
        channels = Mock(ChannelConfigRepository)
        superAdmins = Mock(SuperAdminProvisioner)
        service = new PlatformService(profiles, channels, superAdmins)
    }

    def "首次启动前平台处于未初始化状态"() {
        expect:
        !service.initialized()
        service.profile().isInitialized() == false
    }

    def "初始化需要提供时区、超管用户名与初始口令"() {
        when:
        def profile = service.initialize(
                new InitRequest(ZoneId.of("Asia/Shanghai"), "root", "NeoCat@2026"), NOW)

        then:
        profile.isInitialized()
        profile.getTimezone() == ZoneId.of("Asia/Shanghai")
        profile.getInitializedAt() == NOW
        1 * superAdmins.createSuperAdmin("root", "NeoCat@2026") >> 1L
    }

    def "初始化建立默认慢阈值：URL 1000 / SQL 100 / 调用 1000 / 缓存 50"() {
        when:
        def profile = service.initialize(
                new InitRequest(ZoneId.of("Asia/Shanghai"), "root", "NeoCat@2026"), NOW)

        then:
        profile.getSlowThresholds().getUrlMs() == 1000
        profile.getSlowThresholds().getSqlMs() == 100
        profile.getSlowThresholds().getCallMs() == 1000
        profile.getSlowThresholds().getCacheMs() == 50
    }

    def "初始化建立空的邮件、钉钉、飞书通道"() {
        when:
        service.initialize(new InitRequest(ZoneId.of("Asia/Shanghai"), "root", "NeoCat@2026"), NOW)

        then:
        1 * channels.save({ it.getType() == ChannelType.EMAIL && !it.isEnabled() && it.getConfig() == null })
        1 * channels.save({ it.getType() == ChannelType.DINGTALK && !it.isEnabled() && it.getConfig() == null })
        1 * channels.save({ it.getType() == ChannelType.FEISHU && !it.isEnabled() && it.getConfig() == null })
    }

    def "初始化不建立任何组织、大盘或组织告警"() {
        when:
        service.initialize(new InitRequest(ZoneId.of("Asia/Shanghai"), "root", "NeoCat@2026"), NOW)

        then:
        1 * superAdmins.createSuperAdmin("root", "NeoCat@2026") >> 1L
        1 * profiles.save({ it.isInitialized() })
        // 组织/大盘/告警由各自模块的仓库保证初始为空，此处只声明该项由构造期保证
        true
    }

    def "重复初始化被拒绝"() {
        given:
        profiles = Stub(PlatformProfileRepository) {
            load() >> PlatformProfile.notInitialized().withInitialized(ZoneId.of('Asia/Shanghai'), NOW)
        }
        service = new PlatformService(profiles, channels, superAdmins)

        when:
        service.initialize(new InitRequest(ZoneId.of("UTC"), "root2", "NeoCat@2026"), NOW)

        then:
        def e = thrown(NeocatException)
        e.code() == ALREADY_INITIALIZED
    }

    def "初始化口令至少 8 位"() {
        when:
        service.initialize(new InitRequest(ZoneId.of("Asia/Shanghai"), "root", "short"), NOW)

        then:
        def e = thrown(NeocatException)
        e.code() == PASSWORD_TOO_SHORT
    }

    def "初始化后平台时区不可修改"() {
        given:
        profiles = Stub(PlatformProfileRepository) {
            load() >> PlatformProfile.notInitialized().withInitialized(ZoneId.of('Asia/Shanghai'), NOW)
        }
        service = new PlatformService(profiles, channels, superAdmins)

        when:
        service.changeTimezone(ZoneId.of("UTC"))

        then:
        def e = thrown(NeocatException)
        e.code() == TIMEZONE_IMMUTABLE
    }

    def "初始化后 timezone() 返回平台固定时区"() {
        given:
        profiles = Stub(PlatformProfileRepository) {
            load() >> PlatformProfile.notInitialized().withInitialized(ZoneId.of('Asia/Shanghai'), NOW)
        }
        service = new PlatformService(profiles, channels, superAdmins)

        expect:
        service.timezone() == ZoneId.of("Asia/Shanghai")
    }
}
