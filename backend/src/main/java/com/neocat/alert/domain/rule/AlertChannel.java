package com.neocat.alert.domain.rule;
import org.springframework.modulith.NamedInterface;

/**
 * 通知通道（PRD 06 §10）。
 *
 * <p>一期通道：邮件、钉钉、飞书。
 * **站内预告警不是通道**——它只用于配置页试算，不产生正式触发记录。
 */
@NamedInterface("alert")
public enum AlertChannel {
    EMAIL,
    DINGTALK,
    FEISHU
}
