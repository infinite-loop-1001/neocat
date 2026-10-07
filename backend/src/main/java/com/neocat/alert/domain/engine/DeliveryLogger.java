package com.neocat.alert.domain.engine;

import com.neocat.alert.domain.rule.AlertChannel;
import org.springframework.modulith.NamedInterface;

/**
 * 通知发送失败记录（PRD 06 §10）。
 *
 * <p>发送失败**只写平台运行日志**，不在产品页面提供投递历史。
 * 该接口把「写日志」抽象出来，便于单测断言「失败被记录而不抛异常」。
 */
@FunctionalInterface
@NamedInterface("alert")
public interface DeliveryLogger {

    void failed(long ruleId, AlertChannel channel, Throwable cause, String message);

    static DeliveryLogger noop() {
        return (ruleId, channel, cause, message) -> {
        };
    }
}
