package com.neocat.platform.domain.channel;
import org.springframework.modulith.NamedInterface;

/**
 * 通知通道（PRD 06 §10）：一期仅邮件、钉钉、飞书。
 * 站内预告警不属于通道，它是配置页试算，不产生正式触发记录。
 */
@NamedInterface("platform")
public enum ChannelType {
    EMAIL,
    DINGTALK,
    FEISHU
}
