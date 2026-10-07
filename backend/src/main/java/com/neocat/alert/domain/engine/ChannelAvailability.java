package com.neocat.alert.domain.engine;

import com.neocat.alert.domain.rule.AlertChannel;
import org.springframework.modulith.NamedInterface;

/**
 * 通道可用性查询（PRD 06 §10）。
 *
 * <p>由 platform 模块实现；alert 只依赖该抽象。
 * **管理员未配置好的外部通道不可在规则中选择**。
 */
@FunctionalInterface
@NamedInterface("alert")
public interface ChannelAvailability {

    boolean available(AlertChannel channel);

}
