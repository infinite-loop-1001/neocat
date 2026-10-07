package com.neocat.platform.domain.profile;

import com.neocat.platform.domain.channel.ChannelConfig;
import com.neocat.platform.domain.channel.ChannelConfigRepository;
import com.neocat.platform.domain.channel.ChannelType;
import com.neocat.platform.domain.init.InitRequest;
import com.neocat.platform.domain.init.SuperAdminProvisioner;

import com.neocat.common.error.exception.ConflictException;
import com.neocat.common.error.exception.ValidationException;
import com.neocat.common.error.exception.BusinessRuleException;

import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.Objects;

import static com.neocat.common.error.ErrorCode.ALREADY_INITIALIZED;
import static com.neocat.common.error.ErrorCode.PASSWORD_TOO_SHORT;
import static com.neocat.common.error.ErrorCode.TIMEZONE_IMMUTABLE;

/**
 * 平台初始化与配置用例（PRD 01 §2、PRD 06 §10）。
 */
@org.springframework.stereotype.Service
@org.springframework.modulith.NamedInterface("platform")
public class PlatformService {

    static final int MIN_PASSWORD_LENGTH = 8;

    private final PlatformProfileRepository profiles;

    private final ChannelConfigRepository channels;

    private final SuperAdminProvisioner superAdmins;

    public PlatformService(PlatformProfileRepository profiles, ChannelConfigRepository channels,
                           SuperAdminProvisioner superAdmins) {
        this.profiles = profiles;
        this.channels = channels;
        this.superAdmins = superAdmins;
    }
    /**
     * §2.1 首次部署初始化。
     *
     * <p>一次性建立：固定时区、第一个超管、默认慢阈值（1000/100/1000/50）、
     * 空的邮件/钉钉/飞书通道。仅未初始化时可执行。
     */
    @org.springframework.transaction.annotation.Transactional
    @com.neocat.common.locking.MySqlLocked("metadata")
    public PlatformProfile initialize(InitRequest request, Instant at) {
        if (initialized()) {
            throw new ConflictException(ALREADY_INITIALIZED);
        }
        if (Objects.isNull(request.getAdminPassword()) || request.getAdminPassword().length() < MIN_PASSWORD_LENGTH) {
            throw new ValidationException(PASSWORD_TOO_SHORT, MIN_PASSWORD_LENGTH);
        }
        superAdmins.createSuperAdmin(request.getAdminUsername(), request.getAdminPassword());

        PlatformProfile profile = PlatformProfile.notInitialized()
                .withInitialized(request.getTimezone(), at);
        profiles.save(profile);
        for (ChannelConfig empty : ChannelConfig.emptyAll()) {
            channels.save(empty);
        }
        return profile;
    }
    public PlatformProfile profile() {
        return java.util.Objects.requireNonNullElseGet(profiles.load(), PlatformProfile::notInitialized);
    }
    public boolean initialized() {
        return profile().isInitialized();
    }
    public ZoneId timezone() {
        return profile().getTimezone();
    }
    /**
     * 时区初始化后一期不可修改（PRD 00 §12、PRD 01 §2.1）。
     * 初始化前的首次设定请走 {@link #initialize}。
     */
    @com.neocat.common.locking.MySqlLocked("metadata")
    public void changeTimezone(ZoneId newZone) {
        if (initialized()) {
            throw new BusinessRuleException(TIMEZONE_IMMUTABLE);
        }
        profiles.save(profile().withSlowThresholds(profile().getSlowThresholds()));
    }
    /** 慢阈值变更：只影响后续分析，不重算历史（PRD 03 §9）。 */
    @com.neocat.common.locking.MySqlLocked("metadata")
    public PlatformProfile updateSlowThresholds(SlowThresholds thresholds) {
        PlatformProfile updated = profile().withSlowThresholds(thresholds);
        profiles.save(updated);
        return updated;
    }
    public SlowThresholds slowThresholds() {
        return profile().getSlowThresholds();
    }
    /** 通道配置：未启用的通道不可在告警规则中选择。 */
    @com.neocat.common.locking.MySqlLocked("metadata")
    public List<ChannelConfig> updateChannels(List<ChannelConfig> configs) {
        configs.forEach(channels::save);
        return channels.findAll();
    }
    public List<ChannelConfig> channels() {
        return channels.findAll();
    }
    public boolean channelsAvailable(ChannelType type) {
        return channels.findAll().stream()
                .anyMatch(c -> c.getType() == type && c.isEnabled());
    }
}
