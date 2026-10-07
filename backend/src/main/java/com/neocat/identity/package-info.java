/**
 * 身份与会话模块（PRD 01 §3–4，链路 2–6）。
 *
 * <p>只依赖 {@code core}。账号禁用/启用通过应用事件通知 {@code alert}
 * （移除收件人）与 {@code isOrganization}（恢复成员继承），**不反向依赖**它们。
 */
@org.springframework.modulith.ApplicationModule(
        displayName = "Identity",
        allowedDependencies = {"common", "common :: locking", "common :: error", "common :: http", "common :: config", "common :: time", "common :: queue", "catalog :: internal", "platform :: platform"})
package com.neocat.identity;
