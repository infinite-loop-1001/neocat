/**
 * 平台配置（跨模块共享契约，链路 1）。
 *
 * <p>固定时区、慢阈值、通道可用性由多模块读取，因此本包对外暴露。
 * 其中 {@code PlatformService} 是唯一写入口，保证
 * 「时区初始化后只读」这类不变式不被绕过。
 */
@NamedInterface("platform")
package com.neocat.platform.domain;

import org.springframework.modulith.NamedInterface;
