/**
 * 组织树与成员权限模块（PRD 01 §5–6，链路 7–10）。
 *
 * <p>只依赖 {@code core}。拓扑约束与级联删除所需的资源信息
 * （大盘、组织告警）通过 {@code OrgResourceGateway} 抽象由上层实现，
 * 因此本模块不依赖 {@code dashboard} 或 {@code alert}。
 */
@org.springframework.modulith.ApplicationModule(
        displayName = "Organization",
        allowedDependencies = {"common", "common :: locking", "common :: error", "common :: http", "common :: config", "common :: time", "common :: queue"})
package com.neocat.organization;
