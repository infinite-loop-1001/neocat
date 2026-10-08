/**
 * 平台配置模块（PRD 01 §2、PRD 06 §10，链路 1、23）。
 *
 * <p>只依赖 {@code core}。固定时区、慢阈值、通知通道由本模块持有，
 * 其他模块通过公开接口读取，不直接访问其存储。
 */
@ApplicationModule(
        displayName = "Platform",
        allowedDependencies = {"common", "common :: locking", "common :: error", "common :: config", "common :: time", "common :: queue"})
package com.neocat.platform;

import org.springframework.modulith.ApplicationModule;
