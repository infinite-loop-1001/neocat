package com.neocat.platform.infra;

import com.neocat.platform.domain.channel.ChannelConfig;
import com.neocat.platform.domain.channel.ChannelConfigRepository;
import com.neocat.platform.domain.channel.ChannelType;
import com.neocat.platform.domain.profile.PlatformProfile;
import com.neocat.platform.domain.profile.PlatformProfileRepository;
import com.neocat.platform.domain.profile.SlowThresholds;
import org.springframework.stereotype.Repository;

import java.time.ZoneId;
import java.util.List;
import java.util.Objects;
import java.sql.Timestamp;
import com.neocat.platform.infra.row.ChannelRow;
import com.neocat.platform.infra.row.PlatformRow;

/**
 * 平台档案与通道配置的 MyBatis 适配器
 * （表 {@code nc_platform_profile} 单行、{@code nc_channel_config}）。
 *
 * <p>平台档案是单行表（{@code id = 1}），因此 {@code save} 走 UPDATE，
 * 由初始化流程保证行已存在（初始化脚本写入）。
 */
@Repository
public class PlatformRepositoryAdapter implements PlatformProfileRepository, ChannelConfigRepository {

    private final PlatformMapper mapper;

    public PlatformRepositoryAdapter(PlatformMapper mapper) {
        this.mapper = mapper;
    }

    // ── 平台档案 ─────────────────────────────────────────────

    @Override
    public PlatformProfile load() {
        PlatformRow row = mapper.selectProfile();
        if (Objects.isNull(row)) {
            return null;
        }
        SlowThresholds thresholds = new SlowThresholds(
                row.getSlowUrlMs(), row.getSlowSqlMs(), row.getSlowCallMs(), row.getSlowCacheMs());
        return new PlatformProfile(
                row.isInitialized(),
                ZoneId.of(row.getTimezone()),
                thresholds,
                Objects.isNull(row.getInitializedAt()) ? null : row.getInitializedAt().toInstant());
    }

    @Override
    public void save(PlatformProfile profile) {
        PlatformRow row = new PlatformRow();
        row.setInitialized(profile.isInitialized());
        row.setTimezone(profile.getTimezone().getId());
        row.setSlowUrlMs(profile.getSlowThresholds().getUrlMs());
        row.setSlowSqlMs(profile.getSlowThresholds().getSqlMs());
        row.setSlowCallMs(profile.getSlowThresholds().getCallMs());
        row.setSlowCacheMs(profile.getSlowThresholds().getCacheMs());
        row.setInitializedAt(Objects.isNull(profile.getInitializedAt())
                ? null
                : Timestamp.from(profile.getInitializedAt()));
        mapper.updateProfile(row);
    }

    // ── 通道配置 ─────────────────────────────────────────────

    @Override
    public List<ChannelConfig> findAll() {
        return mapper.selectChannels().stream()
                .map(r -> new ChannelConfig(ChannelType.valueOf(r.getChannel()),
                        r.isEnabled(), r.getConfigJson()))
                .toList();
    }

    @Override
    public ChannelConfig save(ChannelConfig config) {
        ChannelRow row = new ChannelRow();
        row.setChannel(config.getType().name());
        row.setEnabled(config.isEnabled());
        row.setConfigJson(config.getConfig());
        mapper.upsertChannel(row);
        return config;
    }
}