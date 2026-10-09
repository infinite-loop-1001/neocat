package com.neocat.alert.domain.recipient;

import org.springframework.modulith.NamedInterface;

/**
 * 账号与组织事件（PRD 06 §9）。
 *
 * <p>alert 通过订阅这些事件维护收件人，避免依赖 identity / isOrganization 的实现。
 */
@NamedInterface("alert")
public sealed interface RecipientEvent permits UserDisabled, UserEnabled, OrgMembershipChanged, OrgDeleted {

}
