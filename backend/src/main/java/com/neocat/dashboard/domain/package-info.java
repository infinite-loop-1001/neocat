/**
 * 大盘与卡片（跨模块共享契约，链路 24、25、27）。
 *
 * <p>{@code alert} 需要读取卡片详情与卡片公式（组织告警目标跟随公式），
 * 并接收 {@code CardEvent} 通知，因此本包对外暴露。
 */
@NamedInterface("dashboard")
package com.neocat.dashboard.domain;

import org.springframework.modulith.NamedInterface;
