package com.neocat.platform.infra;

import com.neocat.platform.domain.channel.ChannelConfig;
import com.neocat.platform.domain.channel.ChannelConfigRepository;
import com.neocat.platform.domain.channel.ChannelType;
import com.neocat.platform.domain.profile.PlatformProfile;
import com.neocat.platform.domain.profile.PlatformProfileRepository;
import com.neocat.platform.domain.profile.SlowThresholds;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;

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
        if (row == null) {
            return null;
        }
        SlowThresholds thresholds = new SlowThresholds(
                row.getSlowUrlMs(), row.getSlowSqlMs(), row.getSlowCallMs(), row.getSlowCacheMs());
        return new PlatformProfile(
                row.isInitialized(),
                ZoneId.of(row.getTimezone()),
                thresholds,
                row.getInitializedAt() == null ? null : row.getInitializedAt().toInstant());
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
        row.setInitializedAt(profile.getInitializedAt() == null
                ? null
                : java.sql.Timestamp.from(profile.getInitializedAt()));
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
    /** 平台档案行（列名与 nc_platform_profile 一致）。 */
    public static class PlatformRow {
        private boolean initialized;

        private String timezone;

        private int slowUrlMs;

        private int slowSqlMs;

        private int slowCallMs;

        private int slowCacheMs;

        private java.sql.Timestamp initializedAt;

        public boolean isInitialized() {
            return initialized;
        }

        public void setInitialized(boolean initialized) {
            this.initialized = initialized;
        }

        public String getTimezone() {
            return timezone;
        }

        public void setTimezone(String timezone) {
            this.timezone = timezone;
        }

        public int getSlowUrlMs() {
            return slowUrlMs;
        }

        public void setSlowUrlMs(int slowUrlMs) {
            this.slowUrlMs = slowUrlMs;
        }

        public int getSlowSqlMs() {
            return slowSqlMs;
        }

        public void setSlowSqlMs(int slowSqlMs) {
            this.slowSqlMs = slowSqlMs;
        }

        public int getSlowCallMs() {
            return slowCallMs;
        }

        public void setSlowCallMs(int slowCallMs) {
            this.slowCallMs = slowCallMs;
        }

        public int getSlowCacheMs() {
            return slowCacheMs;
        }

        public void setSlowCacheMs(int slowCacheMs) {
            this.slowCacheMs = slowCacheMs;
        }

        public java.sql.Timestamp getInitializedAt() {
            return initializedAt;
        }

        public void setInitializedAt(java.sql.Timestamp initializedAt) {
            this.initializedAt = initializedAt;
        }
    }
    /** 通道配置行（列名与 nc_channel_config 一致）。 */
    public static class ChannelRow {
        private String channel;

        private boolean enabled;

        private String configJson;

        public String getChannel() {
            return channel;
        }

        public void setChannel(String channel) {
            this.channel = channel;
        }

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public String getConfigJson() {
            return configJson;
        }

        public void setConfigJson(String configJson) {
            this.configJson = configJson;
        }
    }
}






