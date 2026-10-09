package com.neocat.platform.infra;

import lombok.Data;

/**
 * 通道配置行（列名与 nc_channel_config 一致）。
 */
@Data
public class ChannelRow {
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
