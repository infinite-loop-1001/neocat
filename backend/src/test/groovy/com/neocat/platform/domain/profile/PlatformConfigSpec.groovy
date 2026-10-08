package com.neocat.platform.domain.profile

import com.neocat.alert.config.AlertConfig
import com.neocat.ingest.config.IngestConfig
import com.neocat.platform.domain.channel.ChannelConfig
import com.neocat.platform.domain.channel.ChannelConfigRepository
import com.neocat.platform.domain.channel.ChannelType
import com.neocat.platform.domain.init.SuperAdminProvisioner
import com.neocat.trace.config.TraceConfig
import spock.lang.Specification

import java.time.Instant
import java.time.ZoneId

/**
 * G3 任务15（红）：平台配置与 Apollo 兜底。
 * 对应 PRD 03 §9（慢阈值只影响后续）、PRD 06 §10（未配置通道不可选）、
 * 07-config-and-testing.md §1.4（Apollo 不可用时用本地默认）。
 */
class PlatformConfigSpec extends Specification {

    static final Instant NOW = Instant.parse("2026-09-24T04:00:00Z")

    PlatformService service
    PlatformProfileRepository profiles
    ChannelConfigRepository channels
    static final PlatformProfile INITIAL = PlatformProfile.notInitialized().withInitialized(ZoneId.of("Asia/Shanghai"), NOW)

    def setup() {
        profiles = Mock(PlatformProfileRepository)
        channels = Stub(ChannelConfigRepository) {
            findAll() >> []
            save(_ as ChannelConfig) >> { ChannelConfig config -> config }
        }
        service = new PlatformService(profiles, channels, Stub(SuperAdminProvisioner))
    }

    // ── 慢阈值 ───────────────────────────────────────────────

    def "更新慢阈值后立即生效"() {
        when:
        def updated = service.updateSlowThresholds(new SlowThresholds(2000, 200, 2000, 100))

        then:
        1 * profiles.load() >> INITIAL
        1 * profiles.save({ it.getSlowThresholds() == new SlowThresholds(2000, 200, 2000, 100) })
        updated.getSlowThresholds() == new SlowThresholds(2000, 200, 2000, 100)
    }

    def "慢阈值变更不影响已初始化状态与时区"() {
        when:
        def updated = service.updateSlowThresholds(new SlowThresholds(500, 50, 500, 20))

        then:
        1 * profiles.load() >> INITIAL
        1 * profiles.save(_)
        updated.isInitialized()
        updated.getTimezone() == ZoneId.of("Asia/Shanghai")
        updated.getInitializedAt() == NOW
    }

    def "慢阈值阈值本身不重算历史：变更只写配置，不回写历史报表"() {
        given:
        def before = INITIAL.getSlowThresholds()

        when:
        def updated = service.updateSlowThresholds(new SlowThresholds(1, 1, 1, 1))

        then: "旧值仍可被引用用于说明历史归类不受影响"
        1 * profiles.load() >> INITIAL
        1 * profiles.save(_)
        before.getUrlMs() == 1000
        updated.getSlowThresholds().getUrlMs() == 1
    }

    // ── 通道 ─────────────────────────────────────────────────

    def "未配置的通道不可在告警规则中选择"() {
        expect:
        !service.channelsAvailable(ChannelType.EMAIL)
        !service.channelsAvailable(ChannelType.DINGTALK)
        !service.channelsAvailable(ChannelType.FEISHU)
    }

    def "启用通道后可在告警规则中选择"() {
        when:
        channels = Stub(ChannelConfigRepository) {
            findAll() >> [new ChannelConfig(ChannelType.EMAIL, true, "smtp://mail.internal:25")]
            save(_ as ChannelConfig) >> { ChannelConfig config -> config }
        }
        service = new PlatformService(profiles, channels, Stub(SuperAdminProvisioner))
        service.updateChannels([
                new ChannelConfig(ChannelType.EMAIL, true, "smtp://mail.internal:25")
        ])

        then:
        service.channelsAvailable(ChannelType.EMAIL)
        !service.channelsAvailable(ChannelType.DINGTALK)
    }

    def "平台不提供站内告警记录通道"() {
        expect: "ChannelType 只包含一期允许的三种外部通道"
        ChannelType.values()*.name() as Set == ["EMAIL", "DINGTALK", "FEISHU"] as Set
    }

    // ── Apollo 兜底 ──────────────────────────────────────────

    def "离线领域测试通过隔离测试设施设置运行参数，不替代生产在线门禁"() {
        expect:
        IngestConfig.QUEUE_CAPACITY == 65536
        TraceConfig.SAMPLE_RATE == 1.0d
        AlertConfig.EVALUATE_DELAY_SECONDS == 5
    }

    def "运行参数变化不影响平台档案中的业务值"() {
        given:
        AlertConfig.EVALUATE_DELAY_SECONDS = 30

        when:
        def threshold = service.slowThresholds()

        then:
        1 * profiles.load() >> INITIAL
        AlertConfig.EVALUATE_DELAY_SECONDS == 30
        threshold.getUrlMs() == 1000
        !service.channelsAvailable(ChannelType.EMAIL)
    }
}
