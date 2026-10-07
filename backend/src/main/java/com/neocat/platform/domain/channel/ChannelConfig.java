package com.neocat.platform.domain.channel;

/**
 * 通道配置（PRD 06 §10）：未配置好的外部通道不可在规则中选择。
 *
 * @param type    通道类型
 * @param enabled 是否已配置且启用
 * @param config  凭据（SMTP / Webhook 等），仅平台管理员可见
 */
@org.springframework.modulith.NamedInterface("platform")
@lombok.Getter
@lombok.EqualsAndHashCode
@lombok.ToString
public class ChannelConfig {
    private final ChannelType type;

    private final boolean enabled;

    private final String config;

    public ChannelConfig(ChannelType type, boolean enabled, String config) {
        this.type = type;
        this.enabled = enabled;
        this.config = config;
    }

    public static ChannelConfig empty(ChannelType type) {
        return new ChannelConfig(type, false, null);
    }
    /** 平台初始化时建立「空的邮件、钉钉、飞书通道配置」。 */
    public static java.util.List<ChannelConfig> emptyAll() {
        return java.util.Arrays.stream(ChannelType.values()).map(ChannelConfig::empty).toList();
    }
}
