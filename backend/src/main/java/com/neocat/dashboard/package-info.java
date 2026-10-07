/**
 * 叶子组织大盘模块（PRD 05，链路 24、25、27）。
 *
 * <p>依赖 {@code core}、{@code query}（卡片取数）、{@code isOrganization}（成员资格）
 * 与 {@code platform}。
 *
 * <p>与 {@code alert} 的联动**不存在编译期依赖**：
 * 卡片目标变化通过 {@code CardEvent} 单向通知 alert，
 * alert 反向读取卡片详情经本模块的 {@code api} 只读接口。
 */
@org.springframework.modulith.ApplicationModule(
        displayName = "Dashboard",
        allowedDependencies = {"common", "common :: locking", "common :: error", "common :: http", "common :: config", "common :: time", "common :: queue", "query :: query", "query :: internal", "organization :: organization", "organization :: internal", "platform :: platform"})
package com.neocat.dashboard;
