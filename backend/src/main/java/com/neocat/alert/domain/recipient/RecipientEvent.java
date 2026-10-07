package com.neocat.alert.domain.recipient;

import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.ToString;
import org.springframework.modulith.NamedInterface;

/**
 * 账号与组织事件（PRD 06 §9）。
 *
 * <p>alert 通过订阅这些事件维护收件人，避免依赖 identity / isOrganization 的实现。
 */
@NamedInterface("alert")
public sealed interface RecipientEvent {

    /** 账号被禁用：从所有相关规则接收人中移除，规则不因此关闭。 */
    @NamedInterface("alert")
    @Getter
    @EqualsAndHashCode
    @ToString
    final class UserDisabled implements RecipientEvent {

        private final long accountId;

        public UserDisabled(long accountId) {
            this.accountId = accountId;
        }

    }
    /** 账号被启用：**不自动恢复**任何告警收件关系。 */
    @NamedInterface("alert")
    @Getter
    @EqualsAndHashCode
    @ToString
    final class UserEnabled implements RecipientEvent {
        private final long accountId;

        public UserEnabled(long accountId) {
            this.accountId = accountId;
        }

    }
    /** 组织成员关系变化。 */
    @NamedInterface("alert")
    @Getter
    @EqualsAndHashCode
    @ToString
    final class OrgMembershipChanged implements RecipientEvent {

        private final long accountId;

        private final long orgId;

        // rules: 类字段需要加注释 (业务含义而不是单纯这个字段的翻译)
        private final boolean granted;

        public OrgMembershipChanged(long accountId, long orgId, boolean granted) {
            this.accountId = accountId;
            this.orgId = orgId;
            this.granted = granted;
        }

    }
    /** 组织被删除：该叶子的组织告警规则失效但保留配置。 */
    @NamedInterface("alert")
    @Getter
    @EqualsAndHashCode
    @ToString
    final class OrgDeleted implements RecipientEvent {

        private final long orgId;

        public OrgDeleted(long orgId) {
            this.orgId = orgId;
        }

    }
}
