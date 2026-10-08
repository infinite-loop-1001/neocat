package com.neocat.platform.domain.channel;
import java.util.Arrays;
import java.util.List;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.ToString;
import org.springframework.modulith.NamedInterface;

/**
 * 通道配置（PRD 06 §10）：未配置好的外部通道不可在规则中选择。
 *
 * @param type    通道类型
 * @param enabled 是否已配置且启用
 * @param config  凭据（SMTP / Webhook 等），仅平台管理员可见
 */
@NamedInterface("platform")
@Getter
@EqualsAndHashCode
@ToString
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
    public static List<ChannelConfig> emptyAll() {
        return Arrays.stream(ChannelType.values()).map(ChannelConfig::empty).toList();
    }
}
