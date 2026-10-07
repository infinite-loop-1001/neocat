package com.neocat.alert.domain.engine;

import org.springframework.modulith.NamedInterface;

/**
 * 通知发送口（PRD 06 §10）。
 *
 * <p>由基础设施实现（邮件/钉钉/飞书）；单测使用记录替身。
 * 发送失败只写运行日志，不向产品页面暴露投递历史。
 */
@FunctionalInterface
@NamedInterface("alert")
public interface Notifier {

    void send(AlertNotification notification);

    static Notifier noop() {
        return notification -> {
        };
    }
}
