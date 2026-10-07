/**
 * 告警模块（PRD 06，链路 26–32）。
 *
 * <p>依赖 {@code core}、{@code query}（分钟点取值）、{@code identity}（收件人校验）、
 * {@code isOrganization}（组织成员资格）与 {@code platform}（通道可用性）。
 *
 * <p>**一期不存在告警触发历史**：本模块只保存规则、条件、收件人与滑动窗口状态，
 * 正式触发只产生通知，不写站内记录（PRD 06 §11）。
 */
@org.springframework.modulith.ApplicationModule(
        displayName = "Alert",
        allowedDependencies = {"common", "common :: locking", "common :: error", "common :: http", "common :: config", "common :: time", "common :: queue", "query :: query", "query :: internal", "identity :: identity", "identity :: internal", "organization :: organization", "organization :: internal", "dashboard :: internal", "platform :: platform", "platform :: internal"})
package com.neocat.alert;
