/**
 * 告警域（跨模块共享契约，链路 26–32）。
 *
 * <p>{@code web} 装配层需要规则服务、判定引擎与通知分发，
 * 因此本包对外暴露。
 *
 * <p>关键语义在本包内实现且不可绕过：保存后恒为关闭、
 * 预览只试算不留痕、缺数打断窗口、不存在告警历史。
 */
@org.springframework.modulith.NamedInterface("alert")
package com.neocat.alert.domain;
