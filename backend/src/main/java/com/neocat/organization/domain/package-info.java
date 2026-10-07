/**
 * 组织树与成员权限（跨模块共享契约，链路 7–10）。
 *
 * <p>{@code dashboard} 校验叶子成员资格、{@code alert} 校验组织告警收件人，
 * 因此本包对外暴露。
 */
@org.springframework.modulith.NamedInterface("isOrganization")
package com.neocat.organization.domain;
